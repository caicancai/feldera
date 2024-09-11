use actix_web::{
    get,
    http::Method,
    web::{Data as WebData, ReqData},
    HttpResponse,
};
use awc::body::MessageBody as _;

use crate::{
    api::ServerState,
    db::{
        storage::Storage as _,
        types::{pipeline::PipelineStatus, tenant::TenantId},
    },
    error::ManagerError,
};

/// Retrieve the metrics of all running pipelines belonging to this tenant.
///
/// The metrics are collected by making individual HTTP requests to `/metrics`
/// endpoint of each pipeline, of which only successful responses are included
/// in the returned list.
#[utoipa::path(
    context_path = "/v0",
    security(("JSON web token (JWT) or API key" = [])),
    responses(
        (status = OK
        , description = "Metrics of all running pipelines belonging to this tenant in Prometheus format"
        , content_type = "text/plain"
        , body = Vec<u8>),
    ),
    tag = "Metrics"
)]
#[get("/metrics")]
pub(crate) async fn get_metrics(
    state: WebData<ServerState>,
    tenant_id: ReqData<TenantId>,
) -> Result<HttpResponse, ManagerError> {
    let pipelines = state.db.lock().await.list_pipelines(*tenant_id).await?;

    const NEWLINE: u8 = b'\n';
    let mut result = Vec::new();

    for pipeline in pipelines {
        if pipeline.deployment_status == PipelineStatus::Running
            || pipeline.deployment_status == PipelineStatus::Paused
        {
            if let Ok(res) = state
                .runner
                .forward_to_pipeline(*tenant_id, &pipeline.name, Method::GET, "metrics", "", None)
                .await
            {
                if res.status().is_success() {
                    if let Ok(bytes) = res.into_body().try_into_bytes() {
                        result.extend(bytes);
                        result.push(NEWLINE);
                    }
                }
            }
        }
    }

    Ok(HttpResponse::Ok().content_type("text/plain").body(result))
}
