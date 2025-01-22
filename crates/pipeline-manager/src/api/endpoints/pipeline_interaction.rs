// API to read from tables/views and write into tables using HTTP
use crate::api::error::ApiError;
use crate::api::examples;
use crate::api::main::ServerState;
use crate::api::util::parse_url_parameter;
#[cfg(not(feature = "feldera-enterprise"))]
use crate::common_error::CommonError;
use crate::db::types::tenant::TenantId;
use crate::error::ManagerError;
use actix_http::StatusCode;
use actix_web::{
    get,
    http::Method,
    post,
    web::{self, Data as WebData, ReqData},
    HttpRequest, HttpResponse,
};
use feldera_types::program_schema::SqlIdentifier;
use log::{debug, info};
use std::time::Duration;

/// Push data to a SQL table.
///
/// The client sends data encoded using the format specified in the `?format=`
/// parameter as a body of the request.  The contents of the data must match
/// the SQL table schema specified in `table_name`
///
/// The pipeline ingests data as it arrives without waiting for the end of
/// the request.  Successful HTTP response indicates that all data has been
/// ingested successfully.
// TODO: implement chunked and batch modes.
#[utoipa::path(
    context_path = "/v0",
    security(("JSON web token (JWT) or API key" = [])),
    params(
        ("pipeline_name" = String, Path, description = "Unique pipeline name"),
        ("table_name" = String, Path,
            description = "SQL table name. Unquoted SQL names have to be capitalized. Quoted SQL names have to exactly match the case from the SQL program."),
        ("force" = bool, Query, description = "When `true`, push data to the pipeline even if the pipeline is paused. The default value is `false`"),
        ("format" = String, Query, description = "Input data format, e.g., 'csv' or 'json'."),
        ("array" = Option<bool>, Query, description = "Set to `true` if updates in this stream are packaged into JSON arrays (used in conjunction with `format=json`). The default values is `false`."),
        ("update_format" = Option<JsonUpdateFormat>, Query, description = "JSON data change event format (used in conjunction with `format=json`).  The default value is 'insert_delete'."),
    ),
    request_body(
        content = String,
        description = "Contains the new input data in CSV.",
        content_type = "text/plain",
    ),
    responses(
        (status = OK
            , description = "Data successfully delivered to the pipeline."
            , content_type = "application/json"),
        (status = BAD_REQUEST
            , description = "Specified pipeline id is not a valid uuid."
            , body = ErrorResponse
            , example = json!(examples::error_invalid_uuid_param())),
        (status = NOT_FOUND
            , description = "Specified pipeline id does not exist."
            , body = ErrorResponse
            , example = json!(examples::error_unknown_pipeline())),
        (status = NOT_FOUND
            , description = "Specified table does not exist."
            , body = ErrorResponse
            // , example = json!(examples::error_unknown_input_table("MyTable"))
            ),
        (status = NOT_FOUND
            , description = "Pipeline is not currently running because it has been shutdown or not yet started."
            , body = ErrorResponse
            , example = json!(examples::error_pipeline_not_running_or_paused())),
        (status = BAD_REQUEST
            , description = "Unknown data format specified in the '?format=' argument."
            , body = ErrorResponse
            // , example = json!(examples::error_unknown_input_format())
            ),
        (status = BAD_REQUEST
            , description = "Error parsing input data."
            , body = ErrorResponse
            // , example = json!(examples::error_parse_errors())
            ),
        (status = INTERNAL_SERVER_ERROR
            , description = "Request failed."
            , body = ErrorResponse),
    ),
    tag = "Pipeline interaction",
)]
#[post("/pipelines/{pipeline_name}/ingress/{table_name}")]
async fn http_input(
    state: WebData<ServerState>,
    tenant_id: ReqData<TenantId>,
    client: WebData<awc::Client>,
    req: HttpRequest,
    body: web::Payload,
) -> Result<HttpResponse, ManagerError> {
    let pipeline_name = parse_url_parameter(&req, "pipeline_name")?;
    let table_name = match req.match_info().get("table_name") {
        None => {
            return Err(ManagerError::from(ApiError::MissingUrlEncodedParam {
                param: "table_name",
            }));
        }
        Some(table_name) => table_name,
    };
    debug!("Table name {table_name:?}");

    let endpoint = format!("ingress/{table_name}");

    state
        .runner
        .forward_streaming_http_request_to_pipeline_by_name(
            *tenant_id,
            &pipeline_name,
            &endpoint,
            req,
            body,
            client.as_ref(),
            None,
        )
        .await
}

/// Subscribe to a stream of updates from a SQL view or table.
///
/// The pipeline responds with a continuous stream of changes to the specified
/// table or view, encoded using the format specified in the `?format=`
/// parameter. Updates are split into `Chunk`s.
///
/// The pipeline continues sending updates until the client closes the
/// connection or the pipeline is shut down.
#[utoipa::path(
    context_path = "/v0",
    security(("JSON web token (JWT) or API key" = [])),
    params(
        ("pipeline_name" = String, Path, description = "Unique pipeline name"),
        ("table_name" = String, Path,
            description = "SQL table name. Unquoted SQL names have to be capitalized. Quoted SQL names have to exactly match the case from the SQL program."),
        ("format" = String, Query, description = "Output data format, e.g., 'csv' or 'json'."),
        ("array" = Option<bool>, Query, description = "Set to `true` to group updates in this stream into JSON arrays (used in conjunction with `format=json`). The default value is `false`"),
        ("backpressure" = Option<bool>, Query, description = r#"Apply backpressure on the pipeline when the HTTP client cannot receive data fast enough.
        When this flag is set to false (the default), the HTTP connector drops data chunks if the client is not keeping up with its output.  This prevents a slow HTTP client from slowing down the entire pipeline.
        When the flag is set to true, the connector waits for the client to receive each chunk and blocks the pipeline if the client cannot keep up."#)
    ),
    responses(
        (status = OK
            , description = "Connection to the endpoint successfully established. The body of the response contains a stream of data chunks."
            , content_type = "application/json"
            , body = Chunk),
        (status = BAD_REQUEST
            , description = "Specified pipeline id is not a valid uuid."
            , body = ErrorResponse
            , example = json!(examples::error_invalid_uuid_param())),
        (status = NOT_FOUND
            , description = "Specified pipeline id does not exist."
            , body = ErrorResponse
            , example = json!(examples::error_unknown_pipeline())),
        (status = NOT_FOUND
            , description = "Specified table or view does not exist."
            , body = ErrorResponse
            // , example = json!(examples::error_unknown_output_table("MyTable"))
            ),
        (status = GONE
            , description = "Pipeline is not currently running because it has been shutdown or not yet started."
            , body = ErrorResponse
            , example = json!(examples::error_pipeline_not_running_or_paused())),
        (status = BAD_REQUEST
            , description = "Unknown data format specified in the '?format=' argument."
            , body = ErrorResponse
            // , example = json!(examples::error_unknown_output_format())
            ),
        (status = INTERNAL_SERVER_ERROR
            , description = "Request failed."
            , body = ErrorResponse),
    ),
    tag = "Pipeline interaction"
)]
#[post("/pipelines/{pipeline_name}/egress/{table_name}")]
async fn http_output(
    state: WebData<ServerState>,
    tenant_id: ReqData<TenantId>,
    client: WebData<awc::Client>,
    req: HttpRequest,
    body: web::Payload,
) -> Result<HttpResponse, ManagerError> {
    let pipeline_name = parse_url_parameter(&req, "pipeline_name")?;
    let table_name = match req.match_info().get("table_name") {
        None => {
            return Err(ManagerError::from(ApiError::MissingUrlEncodedParam {
                param: "table_name",
            }));
        }
        Some(table_name) => table_name,
    };
    let endpoint = format!("egress/{table_name}");
    state
        .runner
        .forward_streaming_http_request_to_pipeline_by_name(
            *tenant_id,
            &pipeline_name,
            &endpoint,
            req,
            body,
            client.as_ref(),
            None,
        )
        .await
}

/// Start (resume) or pause the input connector.
///
/// The following values of the `action` argument are accepted: `start` and `pause`.
///
/// Input connectors can be in either the `Running` or `Paused` state. By default,
/// connectors are initialized in the `Running` state when a pipeline is deployed.
/// In this state, the connector actively fetches data from its configured data
/// source and forwards it to the pipeline. If needed, a connector can be created
/// in the `Paused` state by setting its
/// [`paused`](https://docs.feldera.com/connectors/#generic-attributes) property
/// to `true`. When paused, the connector remains idle until reactivated using the
/// `start` command. Conversely, a connector in the `Running` state can be paused
/// at any time by issuing the `pause` command.
///
/// The current connector state can be retrieved via the
/// `GET /v0/pipelines/{pipeline_name}/stats` endpoint.
///
/// Note that only if both the pipeline *and* the connector state is `Running`,
/// is the input connector active.
/// ```text
/// Pipeline state    Connector state    Connector is active?
/// --------------    ---------------    --------------------
/// Paused            Paused             No
/// Paused            Running            No
/// Running           Paused             No
/// Running           Running            Yes
/// ```
#[utoipa::path(
    context_path = "/v0",
    security(("JSON web token (JWT) or API key" = [])),
    params(
        ("pipeline_name" = String, Path, description = "Unique pipeline name"),
        ("table_name" = String, Path, description = "Unique table name"),
        ("connector_name" = String, Path, description = "Unique input connector name"),
        ("action" = String, Path, description = "Input connector action (one of: start, pause)")
    ),
    responses(
        (status = OK
            , description = "Action has been processed"),
        (status = NOT_FOUND
            , description = "Pipeline with that name does not exist"
            , body = ErrorResponse
            , example = json!(examples::error_unknown_pipeline())),
        (status = NOT_FOUND
            , description = "Table with that name does not exist"
            , body = ErrorResponse),
        (status = NOT_FOUND
            , description = "Input connector with that name does not exist"
            , body = ErrorResponse),
    ),
    tag = "Pipeline interaction"
)]
#[post("/pipelines/{pipeline_name}/tables/{table_name}/connectors/{connector_name}/{action}")]
pub(crate) async fn post_pipeline_input_connector_action(
    state: WebData<ServerState>,
    tenant_id: ReqData<TenantId>,
    path: web::Path<(String, String, String, String)>,
) -> Result<HttpResponse, ManagerError> {
    // Parse the URL path parameters
    let (pipeline_name, table_name, connector_name, action) = path.into_inner();

    // Validate action
    let verb = match action.as_str() {
        "start" => "starting",
        "pause" => "pausing",
        _ => {
            return Err(ApiError::InvalidConnectorAction { action }.into());
        }
    };

    // The table name provided by the user is interpreted as
    // a SQL identifier to account for case (in-)sensitivity
    let actual_table_name = SqlIdentifier::from(&table_name).name();
    let endpoint_name = format!("{actual_table_name}.{connector_name}");

    // URL encode endpoint name to account for special characters
    let encoded_endpoint_name = urlencoding::encode(&endpoint_name).to_string();

    // Forward the action request to the pipeline
    let response = state
        .runner
        .forward_http_request_to_pipeline_by_name(
            *tenant_id,
            &pipeline_name,
            Method::GET,
            &format!("input_endpoints/{encoded_endpoint_name}/{action}"),
            "",
            None,
        )
        .await?;

    // Log only if the response indicates success
    if response.status() == StatusCode::OK {
        info!(
            "Connector action: {verb} pipeline '{pipeline_name}' on table '{table_name}' on connector '{connector_name}' (tenant: {})",
            *tenant_id
        );
    }
    Ok(response)
}

/// Retrieve pipeline logs as a stream.
///
/// The logs stream catches up to the extent of the internally configured per-pipeline
/// circular logs buffer (limited to a certain byte size and number of lines, whichever
/// is reached first). After the catch-up, new lines are pushed whenever they become
/// available.
///
/// The logs stream will end when the pipeline is shut down. It is also possible for the
/// logs stream to end prematurely due to the runner back-end (temporarily) losing
/// connectivity to the pipeline instance (e.g., process). In this case, it is needed
/// to issue again a new request to this endpoint.
#[utoipa::path(
    context_path = "/v0",
    security(("JSON web token (JWT) or API key" = [])),
    params(
        ("pipeline_name" = String, Path, description = "Unique pipeline name"),
    ),
    responses(
        (status = OK
            , description = "Pipeline logs retrieved successfully"
            , content_type = "text/plain"
            , body = Vec<u8>),
        (status = NOT_FOUND
            , description = "Pipeline with that name does not exist"
            , body = ErrorResponse
            , example = json!(examples::error_unknown_pipeline()))
    ),
    tag = "Pipeline interaction"
)]
#[get("/pipelines/{pipeline_name}/logs")]
pub(crate) async fn get_pipeline_logs(
    client: WebData<awc::Client>,
    state: WebData<ServerState>,
    tenant_id: ReqData<TenantId>,
    path: web::Path<String>,
) -> Result<HttpResponse, ManagerError> {
    let pipeline_name = path.into_inner();
    state
        .runner
        .http_streaming_logs_from_pipeline_by_name(&client, *tenant_id, &pipeline_name)
        .await
}

/// Retrieve pipeline statistics (e.g., metrics, performance counters).
#[utoipa::path(
    context_path = "/v0",
    security(("JSON web token (JWT) or API key" = [])),
    params(
        ("pipeline_name" = String, Path, description = "Unique pipeline name"),
    ),
    responses(
        // TODO: implement `ToSchema` for `ControllerStatus`, which is the
        //       actual type returned by this endpoint and move it to feldera-types.
        (status = OK
            , description = "Pipeline metrics retrieved successfully"
            , body = Object),
        (status = NOT_FOUND
            , description = "Pipeline with that name does not exist"
            , body = ErrorResponse
            , example = json!(examples::error_unknown_pipeline())),
        (status = BAD_REQUEST
            , description = "Pipeline is not running or paused"
            , body = ErrorResponse
            , example = json!(examples::error_pipeline_not_running_or_paused()))
    ),
    tag = "Pipeline interaction"
)]
#[get("/pipelines/{pipeline_name}/stats")]
pub(crate) async fn get_pipeline_stats(
    state: WebData<ServerState>,
    tenant_id: ReqData<TenantId>,
    path: web::Path<String>,
    request: HttpRequest,
) -> Result<HttpResponse, ManagerError> {
    let pipeline_name = path.into_inner();
    state
        .runner
        .forward_http_request_to_pipeline_by_name(
            *tenant_id,
            &pipeline_name,
            Method::GET,
            "stats",
            request.query_string(),
            None,
        )
        .await
}

/// Retrieve the circuit performance profile of a running or paused pipeline.
#[utoipa::path(
    context_path = "/v0",
    security(("JSON web token (JWT) or API key" = [])),
    params(
        ("pipeline_name" = String, Path, description = "Unique pipeline name"),
    ),
    responses(
        (status = OK
            , description = "Obtains a circuit performance profile."
            , content_type = "application/zip"
            , body = Object),
        (status = NOT_FOUND
            , description = "Pipeline with that name does not exist"
            , body = ErrorResponse
            , example = json!(examples::error_unknown_pipeline())),
        (status = BAD_REQUEST
            , description = "Pipeline is not running or paused"
            , body = ErrorResponse
            , example = json!(examples::error_pipeline_not_running_or_paused()))
    ),
    tag = "Pipeline interaction"
)]
#[get("/pipelines/{pipeline_name}/circuit_profile")]
pub(crate) async fn get_pipeline_circuit_profile(
    state: WebData<ServerState>,
    tenant_id: ReqData<TenantId>,
    path: web::Path<String>,
    request: HttpRequest,
) -> Result<HttpResponse, ManagerError> {
    let pipeline_name = path.into_inner();
    state
        .runner
        .forward_http_request_to_pipeline_by_name(
            *tenant_id,
            &pipeline_name,
            Method::GET,
            "dump_profile",
            request.query_string(),
            Some(Duration::from_secs(120)),
        )
        .await
}

/// Checkpoint a running or paused pipeline.
#[utoipa::path(
    context_path = "/v0",
    security(("JSON web token (JWT) or API key" = [])),
    params(
        ("pipeline_name" = String, Path, description = "Unique pipeline name"),
    ),
    responses(
        (status = OK
            , description = "Checkpoint completed."),
        (status = NOT_FOUND
            , description = "Pipeline with that name does not exist"
            , body = ErrorResponse
            , example = json!(examples::error_unknown_pipeline())),
        (status = BAD_REQUEST
            , description = "Pipeline is not running or paused, or fault tolerance is not enabled for this pipeline"
            , body = ErrorResponse
            , example = json!(examples::error_pipeline_not_running_or_paused()))
    ),
    tag = "Pipeline interaction"
)]
#[post("/pipelines/{pipeline_name}/checkpoint")]
pub(crate) async fn checkpoint_pipeline(
    state: WebData<ServerState>,
    tenant_id: ReqData<TenantId>,
    path: web::Path<String>,
    request: HttpRequest,
) -> Result<HttpResponse, ManagerError> {
    #[cfg(not(feature = "feldera-enterprise"))]
    {
        let _ = (state, tenant_id, path.into_inner(), request);
        Err(CommonError::EnterpriseFeature("checkpoint").into())
    }

    #[cfg(feature = "feldera-enterprise")]
    {
        let pipeline_name = path.into_inner();
        state
            .runner
            .forward_http_request_to_pipeline_by_name(
                *tenant_id,
                &pipeline_name,
                Method::POST,
                "checkpoint",
                request.query_string(),
                Some(Duration::from_secs(120)),
            )
            .await
    }
}

/// Retrieve the heap profile of a running or paused pipeline.
#[utoipa::path(
    context_path = "/v0",
    security(("JSON web token (JWT) or API key" = [])),
    params(
        ("pipeline_name" = String, Path, description = "Unique pipeline name"),
    ),
    responses(
        (status = OK
            , description = "Pipeline's heap usage profile as a gzipped protobuf that can be inspected by the pprof tool"
            , content_type = "application/protobuf"
            , body = Vec<u8>),
        (status = NOT_FOUND
            , description = "Pipeline with that name does not exist"
            , body = ErrorResponse
            , example = json!(examples::error_unknown_pipeline())),
        (status = BAD_REQUEST
            , description = "Pipeline is not running or paused, or getting a heap profile is not supported on this platform"
            , body = ErrorResponse
            , example = json!(examples::error_pipeline_not_running_or_paused()))
    ),
    tag = "Pipeline interaction"
)]
#[get("/pipelines/{pipeline_name}/heap_profile")]
pub(crate) async fn get_pipeline_heap_profile(
    state: WebData<ServerState>,
    tenant_id: ReqData<TenantId>,
    path: web::Path<String>,
    request: HttpRequest,
) -> Result<HttpResponse, ManagerError> {
    let pipeline_name = path.into_inner();
    state
        .runner
        .forward_http_request_to_pipeline_by_name(
            *tenant_id,
            &pipeline_name,
            Method::GET,
            "heap_profile",
            request.query_string(),
            None,
        )
        .await
}

/// Execute an ad-hoc query in a running or paused pipeline.
#[utoipa::path(
    context_path = "/v0",
    security(("JSON web token (JWT) or API key" = [])),
    params(
        ("pipeline_name" = String, Path, description = "Unique pipeline name"),
        ("sql" = String, Query, description = "The SQL query to execute."),
        ("format" = AdHocResultFormat, Query, description = "Input data format, e.g., 'text', 'json' or 'parquet'."),
    ),
    responses(
        (status = OK
        , description = "Executes an ad-hoc SQL query in a running or paused pipeline. The evaluation is not incremental."
        , content_type = "text/plain"
        , body = Vec<u8>),
        (status = NOT_FOUND
        , description = "Pipeline with that name does not exist"
        , body = ErrorResponse
        , example = json!(examples::error_unknown_pipeline())),
        (status = BAD_REQUEST
        , description = "Pipeline is shutdown or an invalid SQL query was supplied"
        , body = ErrorResponse
        , example = json!(examples::error_pipeline_not_running_or_paused())),
        (status = INTERNAL_SERVER_ERROR
        , description = "A fatal error occurred during query processing (after streaming response was already initiated)"
        , body = ErrorResponse
        , example = json!(examples::error_stream_terminated()))
    ),
    tag = "Pipeline interaction"
)]
#[get("/pipelines/{pipeline_name}/query")]
pub(crate) async fn pipeline_adhoc_sql(
    state: WebData<ServerState>,
    tenant_id: ReqData<TenantId>,
    client: WebData<awc::Client>,
    path: web::Path<String>,
    request: HttpRequest,
    body: web::Payload,
) -> Result<HttpResponse, ManagerError> {
    let pipeline_name = path.into_inner();
    state
        .runner
        .forward_streaming_http_request_to_pipeline_by_name(
            *tenant_id,
            &pipeline_name,
            "query",
            request,
            body,
            client.as_ref(),
            Some(Duration::MAX),
        )
        .await
}
