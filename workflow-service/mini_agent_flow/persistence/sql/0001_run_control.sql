CREATE TABLE schema_migrations (
  version INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  checksum TEXT NOT NULL,
  applied_at_utc TEXT NOT NULL
);

CREATE TABLE workflow_runs (
  run_id TEXT PRIMARY KEY,
  workflow_name TEXT NOT NULL,
  workflow_version TEXT NOT NULL,
  workflow_json TEXT NOT NULL,
  status TEXT NOT NULL,
  current_execution_id TEXT,
  restart_count INTEGER NOT NULL DEFAULT 0,
  started_at_utc TEXT NOT NULL,
  deadline_at_utc TEXT NOT NULL,
  finished_at_utc TEXT,
  result_summary_json TEXT,
  version INTEGER NOT NULL DEFAULT 0,
  created_at_utc TEXT NOT NULL,
  updated_at_utc TEXT NOT NULL
);

CREATE TABLE run_executions (
  execution_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES workflow_runs(run_id) ON DELETE CASCADE,
  epoch INTEGER NOT NULL,
  status TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  owner_lease_until_utc TEXT NOT NULL,
  started_at_utc TEXT NOT NULL,
  finished_at_utc TEXT,
  safe_to_restart INTEGER NOT NULL DEFAULT 1,
  version INTEGER NOT NULL DEFAULT 0,
  UNIQUE(run_id, epoch)
);

CREATE TABLE node_invocations (
  invocation_id TEXT PRIMARY KEY,
  execution_id TEXT NOT NULL REFERENCES run_executions(execution_id) ON DELETE CASCADE,
  node_id TEXT NOT NULL,
  iteration_path_json TEXT NOT NULL DEFAULT '[]',
  attempt INTEGER NOT NULL,
  status TEXT NOT NULL,
  worker_id TEXT,
  idempotent INTEGER NOT NULL DEFAULT 0,
  idempotency_key TEXT,
  deadline_at_utc TEXT,
  lease_until_utc TEXT,
  heartbeat_at_utc TEXT,
  cancel_requested_at_utc TEXT,
  outcome_event_id TEXT,
  safe_error_summary TEXT,
  version INTEGER NOT NULL DEFAULT 0,
  created_at_utc TEXT NOT NULL,
  updated_at_utc TEXT NOT NULL,
  UNIQUE(execution_id, node_id, iteration_path_json, attempt)
);

CREATE TABLE cancel_requests (
  request_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES workflow_runs(run_id) ON DELETE CASCADE,
  execution_id TEXT NOT NULL REFERENCES run_executions(execution_id) ON DELETE CASCADE,
  scope TEXT NOT NULL,
  target_node_id TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL,
  reason TEXT,
  requested_at_utc TEXT NOT NULL,
  applied_at_utc TEXT,
  UNIQUE(execution_id, scope, target_node_id, status)
);

CREATE TABLE poller_leases (
  lease_name TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL,
  lease_until_utc TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE control_events (
  event_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES workflow_runs(run_id) ON DELETE CASCADE,
  execution_id TEXT,
  invocation_id TEXT,
  event_type TEXT NOT NULL,
  deduplication_key TEXT NOT NULL UNIQUE,
  safe_payload_json TEXT NOT NULL,
  occurred_at_utc TEXT NOT NULL
);

CREATE INDEX idx_runs_status_deadline
  ON workflow_runs(status, deadline_at_utc);
CREATE INDEX idx_runs_status_finished
  ON workflow_runs(status, finished_at_utc);
CREATE INDEX idx_executions_status_owner_lease
  ON run_executions(status, owner_lease_until_utc);
CREATE INDEX idx_invocations_status_deadline
  ON node_invocations(status, deadline_at_utc);
CREATE INDEX idx_invocations_status_lease
  ON node_invocations(status, lease_until_utc);
CREATE INDEX idx_cancel_execution_status
  ON cancel_requests(execution_id, status);
