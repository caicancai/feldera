use crate::db::storage::Storage;
use crate::db::storage_postgres::StoragePostgres;
use crate::db::types::pipeline::PipelineStatus;
use crate::error::ManagerError;
use crate::runner::RunnerApi;
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
    let pipelines = db.list_pipeline_ids_across_all_tenants().await?;
    for (tenant_id, pipeline_id) in pipelines {
        let rts = db.get_pipeline_by_id(tenant_id, pipeline_id).await?;

        // Get the metrics for all running pipelines, don't write anything
        // if the request fails.
        if rts.deployment_status == PipelineStatus::Running {
            if let Ok(r) = RunnerApi::pipeline_http_request(
                pipeline_id,
                Method::GET,
                "metrics",
                &rts.deployment_location.unwrap(),
            ) // TODO: unwrap
            .await
            {
                if r.status().is_success() {
                    if let Ok(r) = r.text().await {
                        buffer += &r;
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
