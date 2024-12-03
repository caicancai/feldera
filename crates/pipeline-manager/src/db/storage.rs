use crate::db::error::DBError;
use crate::db::types::api_key::{ApiKeyDescr, ApiPermission};
use crate::db::types::common::Version;
use crate::db::types::pipeline::{ExtendedPipelineDescr, PipelineDescr, PipelineId};
use crate::db::types::program::{ProgramConfig, ProgramInfo, SqlCompilerMessage};
use crate::db::types::tenant::TenantId;
use async_trait::async_trait;
use feldera_types::config::{PipelineConfig, RuntimeConfig};
use feldera_types::error::ErrorResponse;
use uuid::Uuid;

/// The [`Storage`] trait has all methods the API uses to interact with storage.
/// The implementation of these methods varies depending on the backing storage.
/// Although we have only one supported storage back-end (Postgres), we use a trait
/// to define the interface such that we can mock the storage layer in tests.
#[async_trait]
pub(crate) trait Storage {
    /// Checks whether the database can be connected to.
    async fn check_connection(&self) -> Result<(), DBError>;

    /// Retrieves the tenant identifier for a given tenant (name, provider).
    /// If there does not yet exist a tenant named (name, provider), it is created.
    async fn get_or_create_tenant_id(
        &self,
        new_id: Uuid, // Used only if the tenant does not yet exist
        name: String,
        provider: String,
    ) -> Result<TenantId, DBError>;

    /// Retrieves the list of all API keys.
    async fn list_api_keys(&self, tenant_id: TenantId) -> Result<Vec<ApiKeyDescr>, DBError>;

    /// Retrieves an API key by name.
    async fn get_api_key(&self, tenant_id: TenantId, name: &str) -> Result<ApiKeyDescr, DBError>;

    /// Deletes an API key by name.
    async fn delete_api_key(&self, tenant_id: TenantId, name: &str) -> Result<(), DBError>;

    /// Persists an SHA-256 hash of an API key in the database.
    async fn store_api_key_hash(
        &self,
        tenant_id: TenantId,
        id: Uuid,
        name: &str,
        key: &str,
        permissions: Vec<ApiPermission>,
    ) -> Result<(), DBError>;

    /// Validates an API key against the database by comparing its SHA-256 hash
    /// against the stored value.
    async fn validate_api_key(&self, key: &str) -> Result<(TenantId, Vec<ApiPermission>), DBError>;

    /// Retrieves a list of pipelines as extended descriptors.
    async fn list_pipelines(
        &self,
        tenant_id: TenantId,
    ) -> Result<Vec<ExtendedPipelineDescr>, DBError>;

    /// Retrieves a pipeline as extended descriptor.
    async fn get_pipeline(
        &self,
        tenant_id: TenantId,
        name: &str,
    ) -> Result<ExtendedPipelineDescr, DBError>;

    /// Retrieves a pipeline as extended descriptor using its identifier.
    async fn get_pipeline_by_id(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
    ) -> Result<ExtendedPipelineDescr, DBError>;

    /// Creates a new pipeline.
    async fn new_pipeline(
        &self,
        tenant_id: TenantId,
        new_id: Uuid,
        platform_version: &str,
        pipeline: PipelineDescr,
    ) -> Result<ExtendedPipelineDescr, DBError>;

    /// Creates a new pipeline if one with that name does not exist yet.
    /// If it already exists, update the existing one.
    /// The boolean returned is true iff the pipeline was newly created.
    async fn new_or_update_pipeline(
        &self,
        tenant_id: TenantId,
        new_id: Uuid, // Only used if the pipeline happens to not exist
        original_name: &str,
        platform_version: &str,
        pipeline: PipelineDescr,
    ) -> Result<(bool, ExtendedPipelineDescr), DBError>;

    /// Updates an existing pipeline.
    #[allow(clippy::too_many_arguments)]
    async fn update_pipeline(
        &self,
        tenant_id: TenantId,
        original_name: &str,
        name: &Option<String>,
        description: &Option<String>,
        platform_version: &str,
        runtime_config: &Option<RuntimeConfig>,
        program_code: &Option<String>,
        udf_rust: &Option<String>,
        udf_toml: &Option<String>,
        program_config: &Option<ProgramConfig>,
    ) -> Result<ExtendedPipelineDescr, DBError>;

    /// Deletes an existing pipeline.
    async fn delete_pipeline(
        &self,
        tenant_id: TenantId,
        pipeline_name: &str,
    ) -> Result<PipelineId, DBError>;

    /// Transitions program status to `Pending`.
    async fn transit_program_status_to_pending(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        program_version_guard: Version,
    ) -> Result<(), DBError>;

    /// Transitions program status to `CompilingSql`.
    async fn transit_program_status_to_compiling_sql(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        program_version_guard: Version,
    ) -> Result<(), DBError>;

    /// Transitions program status to `SqlCompiled`.
    async fn transit_program_status_to_sql_compiled(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        program_version_guard: Version,
        program_info: &ProgramInfo,
    ) -> Result<(), DBError>;

    /// Transitions program status to `CompilingRust`.
    async fn transit_program_status_to_compiling_rust(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        program_version_guard: Version,
    ) -> Result<(), DBError>;

    /// Transitions program status to `Success`.
    async fn transit_program_status_to_success(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        program_version_guard: Version,
        program_binary_source_checksum: &str,
        program_binary_integrity_checksum: &str,
        program_binary_url: &str,
    ) -> Result<(), DBError>;

    /// Transitions program status to `SqlError`.
    async fn transit_program_status_to_sql_error(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        program_version_guard: Version,
        internal_sql_error: Vec<SqlCompilerMessage>,
    ) -> Result<(), DBError>;

    /// Transitions program status to `RustError`.
    async fn transit_program_status_to_rust_error(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        program_version_guard: Version,
        internal_rust_error: &str,
    ) -> Result<(), DBError>;

    /// Transitions program status to `SystemError`.
    async fn transit_program_status_to_system_error(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        program_version_guard: Version,
        internal_system_error: &str,
    ) -> Result<(), DBError>;

    /// Sets deployment desired status to `Running`.
    async fn set_deployment_desired_status_running(
        &self,
        tenant_id: TenantId,
        pipeline_name: &str,
    ) -> Result<(), DBError>;

    /// Sets deployment desired status to `Paused`.
    async fn set_deployment_desired_status_paused(
        &self,
        tenant_id: TenantId,
        pipeline_name: &str,
    ) -> Result<(), DBError>;

    /// Sets deployment desired status to `Shutdown`.
    async fn set_deployment_desired_status_shutdown(
        &self,
        tenant_id: TenantId,
        pipeline_name: &str,
    ) -> Result<(), DBError>;

    /// Transitions deployment status to `Provisioning`.
    async fn transit_deployment_status_to_provisioning(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        deployment_config: PipelineConfig,
    ) -> Result<(), DBError>;

    /// Transitions deployment status to `Initializing`.
    async fn transit_deployment_status_to_initializing(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        deployment_location: &str,
    ) -> Result<(), DBError>;

    /// Transitions deployment status to `Running`.
    async fn transit_deployment_status_to_running(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
    ) -> Result<(), DBError>;

    /// Transitions deployment status to `Paused`.
    async fn transit_deployment_status_to_paused(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
    ) -> Result<(), DBError>;

    /// Transitions deployment status to `Unavailable`.
    async fn transit_deployment_status_to_unavailable(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
    ) -> Result<(), DBError>;

    /// Transitions deployment status to `ShuttingDown`.
    async fn transit_deployment_status_to_shutting_down(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
    ) -> Result<(), DBError>;

    /// Transitions deployment status to `Shutdown`.
    async fn transit_deployment_status_to_shutdown(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
    ) -> Result<(), DBError>;

    /// Transitions deployment status to `Failed`.
    async fn transit_deployment_status_to_failed(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        deployment_error: &ErrorResponse,
    ) -> Result<(), DBError>;

    /// Retrieves a list of all pipeline ids across all tenants.
    async fn list_pipeline_ids_across_all_tenants(
        &self,
    ) -> Result<Vec<(TenantId, PipelineId)>, DBError>;

    /// Retrieves a list of all pipeline ids across all tenants.
    async fn list_pipelines_across_all_tenants(
        &self,
    ) -> Result<Vec<(TenantId, ExtendedPipelineDescr)>, DBError>;

    /// Determines what to do with pipelines that are `Pending` and `CompilingSql`.
    ///
    /// If the platform version is the current one, only `CompilingSql` is reset to `Pending`
    /// such that the SQL compiler can pick it up again.
    ///
    /// If the platform version is not the current one, its `platform_version` will be updated and
    /// the `program_status` will be set back to `Pending` (if not already) such that the SQL
    /// compiler can pick it up again.
    async fn clear_ongoing_sql_compilation(&self, platform_version: &str) -> Result<(), DBError>;

    /// Retrieves the pipeline which is shutdown, whose program status has been Pending
    /// for the longest, and is of the current platform version. Returns `None` if none is found.
    async fn get_next_sql_compilation(
        &self,
        platform_version: &str,
    ) -> Result<Option<(TenantId, ExtendedPipelineDescr)>, DBError>;

    /// Determines what to do with pipelines that are `SqlCompiled` and `CompilingRust`.
    ///
    /// If the platform version is the current one, only `CompilingRust` is reset to `SqlCompiled`
    /// such that the Rust compiler can pick it up again.
    ///
    /// If the platform version is not the current one, its `platform_version` will be updated and
    /// the `program_status` will be set back to `Pending` such that the Rust compiler can pick it
    /// up again.
    async fn clear_ongoing_rust_compilation(&self, platform_version: &str) -> Result<(), DBError>;

    /// Retrieves the pipeline which is shutdown, whose program status has been SqlCompiled
    /// for the longest, and is of the current platform version. Returns `None` if none is found.
    async fn get_next_rust_compilation(
        &self,
        platform_version: &str,
    ) -> Result<Option<(TenantId, ExtendedPipelineDescr)>, DBError>;

    /// Retrieves the list of fully compiled pipeline programs (pipeline identifier, program version,
    /// program binary source checksum, program binary integrity checksum) across all tenants.
    async fn list_pipeline_programs_across_all_tenants(
        &self,
    ) -> Result<Vec<(PipelineId, Version, String, String)>, DBError>;
}
