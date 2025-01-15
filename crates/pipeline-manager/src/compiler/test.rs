use crate::compiler::sql_compiler::{attempt_end_to_end_sql_compilation, cleanup_sql_compilation};
use crate::compiler::util::{list_content, read_file_content};
use crate::config::{CommonConfig, CompilerConfig};
use crate::db::storage::Storage;
use crate::db::storage_postgres::StoragePostgres;
use crate::db::types::pipeline::{ExtendedPipelineDescr, PipelineDescr, PipelineId};
use crate::db::types::program::{CompilationProfile, ProgramInfo, ProgramStatus};
use crate::db::types::tenant::TenantId;
use serde_json::json;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tempfile::TempDir;
use tokio::sync::Mutex;
use uuid::Uuid;

/// Structure to help with creating compiler tests, avoiding repeating
/// boilerplate code of setting up configuration and database, as well as
/// common database interactions and in general checks.
pub(crate) struct CompilerTest {
    pub(crate) common_config: CommonConfig,
    pub(crate) compiler_config: CompilerConfig,
    pub(crate) db: Arc<Mutex<StoragePostgres>>,
    pub(crate) sql_workdir: PathBuf,
    pub(crate) rust_workdir: PathBuf,
    _compiler_tempdir: TempDir,
    #[cfg(feature = "pg-embed")]
    _db_tempdir: TempDir,
}

impl CompilerTest {
    pub(crate) async fn new() -> CompilerTest {
        // Test configuration
        let compiler_tempdir = TempDir::new().unwrap();
        let workdir = compiler_tempdir.path().to_str().unwrap();
        let platform_version = "v0";
        let common_config = CommonConfig {
            platform_version: platform_version.to_string(),
        };
        let compiler_config = CompilerConfig {
            sql_compiler_home: "../../sql-to-dbsp-compiler".to_owned(),
            dbsp_override_path: "not-used".to_owned(),
            compilation_profile: CompilationProfile::Optimized,
            precompile: false,
            compiler_working_directory: workdir.to_owned(),
            binary_ref_host: "127.0.0.1".to_string(),
            binary_ref_port: 8085,
        };

        // Test in-memory database
        let (db, _db_tempdir) = crate::db::test::setup_pg().await;
        let db = Arc::new(Mutex::new(db));

        // Path of the SQL compiler working directory
        let sql_workdir = Path::new(&compiler_config.compiler_working_directory)
            .join("sql-compilation")
            .to_path_buf();

        // Path of the Rust compiler working directory
        let rust_workdir = Path::new(&compiler_config.compiler_working_directory)
            .join("rust-compilation")
            .to_path_buf();

        Self {
            common_config,
            compiler_config,
            db,
            sql_workdir,
            rust_workdir,
            _compiler_tempdir: compiler_tempdir,
            #[cfg(feature = "pg-embed")]
            _db_tempdir,
        }
    }

    /// Creates a pipeline in the database under the provided name.
    pub(crate) async fn create_pipeline(
        &self,
        tenant_id: TenantId,
        name: &str,
        platform_version: &str,
        program_code: &str,
    ) -> PipelineId {
        let pipeline_id = PipelineId(Uuid::now_v7());
        self.db
            .lock()
            .await
            .new_pipeline(
                tenant_id,
                pipeline_id.0,
                platform_version,
                PipelineDescr {
                    name: name.to_string(),
                    description: "not-used".to_string(),
                    runtime_config: json!({}),
                    program_code: program_code.to_string(),
                    udf_rust: "not-used".to_string(),
                    udf_toml: "not-used".to_string(),
                    program_config: json!({}),
                },
            )
            .await
            .unwrap();
        pipeline_id
    }

    /// Deletes the pipeline with the provided name from the database.
    /// Pipeline identifier is used to check the correct one is deleted.
    pub(crate) async fn delete_pipeline(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        pipeline_name: &str,
    ) {
        assert_eq!(
            self.db
                .lock()
                .await
                .delete_pipeline(tenant_id, pipeline_name)
                .await
                .unwrap(),
            pipeline_id
        );
    }

    /// Retrieves pipeline descriptor from the database.
    pub(crate) async fn get_pipeline(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
    ) -> ExtendedPipelineDescr {
        self.db
            .lock()
            .await
            .get_pipeline_by_id(tenant_id, pipeline_id)
            .await
            .unwrap()
    }

    /// Performs a tick as-if it was the SQL compiler task,
    /// but without the perpetual loop with interval waiting.
    pub(crate) async fn sql_compiler_tick(&self) {
        cleanup_sql_compilation(&self.compiler_config, self.db.clone())
            .await
            .unwrap();
        attempt_end_to_end_sql_compilation(
            &self.common_config,
            &self.compiler_config,
            self.db.clone(),
        )
        .await
        .unwrap();
    }

    /// Checks that the SQL compiler working directory is empty.
    pub(crate) async fn sql_compiler_check_is_empty(&self) {
        assert_eq!(list_content(&self.sql_workdir).await.unwrap(), vec![]);
    }

    /// Used to check whether the SQL compilation outcome was successful by retrieving
    /// the pipeline descriptor from the database and checking its program status.
    /// Subsequently, read the files in the compilation directory. These are then
    /// compared and sanity checked. Returns the pipeline descriptor and content
    /// of files for further checks.
    pub(crate) async fn check_outcome_sql_compiled(
        &self,
        tenant_id: TenantId,
        pipeline_id: PipelineId,
        program_code: &str,
    ) -> (
        ExtendedPipelineDescr,
        String,
        String,
        String,
        String,
        String,
        String,
    ) {
        // Retrieve pipeline descriptor
        let pipeline_descr = self.get_pipeline(tenant_id, pipeline_id).await;

        // Program status should match
        assert_eq!(pipeline_descr.program_status, ProgramStatus::SqlCompiled);

        // Pipeline directory should be the only one present in the SQL working directory
        let list = list_content(&self.sql_workdir).await.unwrap();
        let pipeline_dir_name = format!("pipeline-{pipeline_id}");
        let pipeline_dir = self.sql_workdir.join(&pipeline_dir_name);
        assert_eq!(list, vec![(pipeline_dir.clone(), Some(pipeline_dir_name))]);

        // Get content of pipeline SQL compilation directory
        let content_pipeline_dir: Vec<String> = list_content_as_sorted_names(&pipeline_dir).await;
        assert_eq!(
            content_pipeline_dir,
            vec![
                "main.rs",
                "program.sql",
                "schema.json",
                "stderr.log",
                "stdout.log",
                "stubs.rs"
            ]
        );
        let content_main_rs = read_file_content(&pipeline_dir.join("main.rs"))
            .await
            .unwrap();
        let content_program_sql = read_file_content(&pipeline_dir.join("program.sql"))
            .await
            .unwrap();
        let content_schema_json = read_file_content(&pipeline_dir.join("schema.json"))
            .await
            .unwrap();
        let content_stderr_log = read_file_content(&pipeline_dir.join("stderr.log"))
            .await
            .unwrap();
        let content_stdout_log = read_file_content(&pipeline_dir.join("stdout.log"))
            .await
            .unwrap();
        let content_stubs_rs = read_file_content(&pipeline_dir.join("stubs.rs"))
            .await
            .unwrap();

        // Basic sanity check for file content
        assert_ne!(content_main_rs, "");
        assert_eq!(
            content_main_rs,
            serde_json::from_value::<ProgramInfo>(pipeline_descr.program_info.clone().unwrap())
                .unwrap()
                .main_rust
        );
        assert_eq!(content_program_sql, program_code);
        assert_ne!(content_schema_json, "");
        assert_eq!(content_stderr_log, "");
        assert_eq!(content_stdout_log, "");
        assert_ne!(content_stubs_rs, "");

        (
            pipeline_descr,
            content_main_rs,
            content_program_sql,
            content_schema_json,
            content_stderr_log,
            content_stdout_log,
            content_stubs_rs,
        )
    }
}

/// Lists the content of a directory as the names therein (sorted).
/// Panics if the directory content cannot be retrieved or an entry does not possess a name.
pub(crate) async fn list_content_as_sorted_names(dir: &Path) -> Vec<String> {
    let mut content: Vec<String> = list_content(dir)
        .await
        .expect("Unable to retrieve directory content")
        .iter()
        .map(|(path, name)| {
            name.clone()
                .unwrap_or_else(|| panic!("Path {path:?} does not have a name"))
        })
        .collect();
    content.sort();
    content
}
