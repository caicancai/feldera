use crate::db::storage::Storage;
use crate::db::storage_postgres::StoragePostgres;
use crate::db::types::pipeline::PipelineStatus;
use crate::error::ManagerError;
use crate::runner::error::RunnerError;
use crate::runner::interaction::RunnerInteraction;
use ::metrics::{describe_histogram, Unit};
use actix_web::{
    get,
    http::{header::ContentType, Method},
    web, HttpResponse, HttpServer, Responder,
};
use metrics_exporter_prometheus::{PrometheusBuilder, PrometheusHandle};
use std::sync::{Arc, OnceLock};
use tokio::sync::Mutex;

pub(crate) const COMPILE_LATENCY_SQL: &str = "feldera.manager.compile_latency_sql";
pub(crate) const COMPILE_LATENCY_RUST: &str = "feldera.manager.compile_latency_rust";

/// Initialize metrics.
pub fn init() -> PrometheusHandle {
    describe_histogram!(
        COMPILE_LATENCY_RUST,
        Unit::Seconds,
        "Compilation latency Rust and status (success vs error)"
    );
    describe_histogram!(
        COMPILE_LATENCY_SQL,
        Unit::Seconds,
        "Compilation latency SQL and status (success vs error)"
    );

    install_metrics_recorder()
}

/// Install a Prometheus recorder.
fn install_metrics_recorder() -> PrometheusHandle {
    static METRIC_HANDLE: OnceLock<PrometheusHandle> = OnceLock::new();
    METRIC_HANDLE
        .get_or_init(|| {
            PrometheusBuilder::new()
                .install_recorder()
                .expect("failed to install metrics exporter")
        })
        .clone()
}

/// Create a scrape endpoint for metrics on http://0.0.0.0:8081/metrics
pub async fn create_endpoint(manager_metrics: PrometheusHandle, db: Arc<Mutex<StoragePostgres>>) {
    let db = web::Data::new(db);
    let manager_metrics = web::Data::new(manager_metrics);

    let _http = tokio::spawn(
        HttpServer::new(move || {
            actix_web::App::new()
                .app_data(db.clone())
                .app_data(manager_metrics.clone())
                .service(metrics)
        })
        .bind(("0.0.0.0", 8081))
        .unwrap()
        .run(),
    );
}

/// A prometheus-compatible metrics scrape endpoint.
#[get("/metrics")]
async fn metrics(
    db: web::Data<Arc<Mutex<StoragePostgres>>>,
    manager_metrics: web::Data<PrometheusHandle>,
) -> Result<impl Responder, ManagerError> {
    let mut buffer = String::new();
    let db = db.lock().await;
    let pipelines = db
        .list_pipelines_across_all_tenants_for_monitoring()
        .await?;
    for (_tenant_id, pipeline) in pipelines {
        // Get the metrics for all running pipelines,
        // don't write anything if the request fails.
        if pipeline.deployment_status == PipelineStatus::Running {
            let location = pipeline
                .deployment_location
                .ok_or(RunnerError::PipelineMissingDeploymentLocation)?;
            if let Ok((_url, response)) = RunnerInteraction::http_request_to_pipeline(
                pipeline.id,
                Some(pipeline.name.clone()),
                &location,
                Method::GET,
                "metrics",
                "",
                None,
            )
            .await
            {
                if response.status().is_success() {
                    if let Ok(response_text) = response.text().await {
                        buffer += &response_text;
                    }
                }
            }
        }
    }
    // Finally, add pipeline-manager metrics
    buffer += &manager_metrics.render();

    Ok(HttpResponse::Ok()
        .content_type(ContentType::plaintext())
        .body(buffer))
}
