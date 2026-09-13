CREATE TABLE trace_events (
  trace_event_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES workflow_runs(run_id) ON DELETE CASCADE,
  execution_id TEXT NOT NULL REFERENCES run_executions(execution_id) ON DELETE CASCADE,
  sequence INTEGER NOT NULL,
  node_id TEXT,
  status TEXT NOT NULL,
  schema_version TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  payload_truncated INTEGER NOT NULL DEFAULT 0,
  occurred_at_utc TEXT NOT NULL,
  UNIQUE(execution_id, sequence)
);

CREATE TABLE context_snapshots (
  snapshot_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES workflow_runs(run_id) ON DELETE CASCADE,
  execution_id TEXT NOT NULL REFERENCES run_executions(execution_id) ON DELETE CASCADE,
  sequence INTEGER NOT NULL,
  redacted_context_json TEXT NOT NULL,
  byte_size INTEGER NOT NULL,
  created_at_utc TEXT NOT NULL,
  UNIQUE(execution_id, sequence)
);

CREATE TABLE node_outputs (
  output_id TEXT PRIMARY KEY,
  execution_id TEXT NOT NULL REFERENCES run_executions(execution_id) ON DELETE CASCADE,
  invocation_id TEXT NOT NULL REFERENCES node_invocations(invocation_id) ON DELETE CASCADE,
  node_id TEXT NOT NULL,
  output_json TEXT NOT NULL,
  byte_size INTEGER NOT NULL,
  created_at_utc TEXT NOT NULL,
  UNIQUE(invocation_id)
);

CREATE INDEX idx_trace_execution_sequence
  ON trace_events(execution_id, sequence);
