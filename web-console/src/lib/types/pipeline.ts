import {
  InputEndpointConfig,
  OutputEndpointConfig,
  Pipeline as RawPipeline,
  PipelineRuntimeState,
  RuntimeConfig
} from '$lib/services/manager'

export type ControllerStatus = {
  global_config: RuntimeConfig
  global_metrics: GlobalMetrics
  inputs: InputEndpointStatus[]
  outputs: OutputEndpointStatus[]
}

export interface GlobalConfig {
  workers: number
  cpu_profiler: boolean
  min_batch_size_records: number
  max_buffering_delay_usecs: number
}

export interface GlobalMetrics {
  rss_bytes: number
  buffered_input_records: number
  total_input_records: number
  total_processed_records: number
  pipeline_complete: boolean
}

export type GlobalMetricsTimestamp = GlobalMetrics & {
  timeMs: number
}

export type InputEndpointStatus = {
  endpoint_name: string
  config: InputEndpointConfig
  metrics: InputEndpointMetrics
  is_fault_tolerant: boolean
}

export interface InputEndpointMetrics {
  total_bytes: number
  total_records: number
  buffered_bytes: number
  buffered_records: number
  num_transport_errors: number
  num_parse_errors: number
  end_of_input: boolean
}

export type OutputEndpointStatus = {
  endpoint_name: string
  config: OutputEndpointConfig
  metrics: OutputEndpointMetrics
  is_fault_tolerant: boolean
}

export interface OutputEndpointMetrics {
  transmitted_records: number
  transmitted_bytes: number
  buffered_records: number
  buffered_batches: number
  num_encode_errors: number
  num_transport_errors: number
  total_processed_input_records: number
}

export interface ConnectorStatus {
  endpoint_name: string
  config: object
  metrics: InputEndpointMetrics | OutputEndpointMetrics
  fatal_error: string | null
}

export enum PipelineStatus {
  // Shouldn't happen, means we haven't put it in the map
  UNKNOWN = 'Unknown',
  // Maps to PipelineStatus.SHUTDOWN
  SHUTDOWN = 'Ready to run',
  // Maps to PipelineStatus.PROVISIONING
  PROVISIONING = 'Provisioning',
  // Maps to PipelineStatus.INITIALIZING
  INITIALIZING = 'Creating',
  CREATE_FAILURE = 'Create failed',
  STARTING = 'Starting',
  STARTUP_FAILURE = 'Start failed',
  // Maps to PipelineStatus.RUNNING
  RUNNING = 'Running',
  PAUSING = 'Pausing',
  // Maps to PipelineStatus.PAUSED
  PAUSED = 'Paused',
  // Maps to PipelineStatus.FAILED
  FAILED = 'Failed',
  // Maps to PipelineStatus.SHUTTING_DOWN
  SHUTTING_DOWN = 'Shutting dwn'
}

export type PipelineWithStatus<Field extends string, Status> = Omit<RawPipeline, 'state'> & {
  state: Omit<PipelineRuntimeState, Field> & {
    [status in Field]: Status
  }
}

export type Pipeline = PipelineWithStatus<'current_status', PipelineStatus>
