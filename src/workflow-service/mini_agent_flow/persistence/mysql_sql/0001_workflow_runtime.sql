CREATE TABLE IF NOT EXISTS workflow_run (
    run_id CHAR(36) PRIMARY KEY,
    assistant_message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    workflow_type VARCHAR(64) NOT NULL,
    workflow_version VARCHAR(32) NOT NULL,
    request_context_hash CHAR(64) NOT NULL,
    request_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_execution_id CHAR(36) NOT NULL,
    execution_epoch INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    owner_id VARCHAR(128) NULL,
    owner_lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_workflow_run_dispatch (status, owner_lease_until, created_at),
    INDEX idx_workflow_run_session (user_id, session_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS workflow_execution (
    execution_id CHAR(36) PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    epoch INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    owner_id VARCHAR(128) NULL,
    owner_lease_until DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_workflow_execution_epoch (run_id, epoch),
    CONSTRAINT fk_workflow_execution_run FOREIGN KEY (run_id) REFERENCES workflow_run(run_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS workflow_plan (
    plan_id CHAR(36) PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    execution_id CHAR(36) NOT NULL,
    plan_version VARCHAR(32) NOT NULL,
    plan_json JSON NOT NULL,
    plan_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_workflow_plan_run FOREIGN KEY (run_id) REFERENCES workflow_run(run_id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_plan_execution FOREIGN KEY (execution_id) REFERENCES workflow_execution(execution_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS workflow_node_invocation (
    invocation_id CHAR(36) PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    execution_id CHAR(36) NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    attempt INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    worker_id VARCHAR(128) NULL,
    lease_until DATETIME(6) NULL,
    safe_error_summary VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_workflow_node_attempt (execution_id, node_id, attempt),
    UNIQUE KEY uk_workflow_node_idempotency (idempotency_key),
    CONSTRAINT fk_workflow_node_run FOREIGN KEY (run_id) REFERENCES workflow_run(run_id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_node_execution FOREIGN KEY (execution_id) REFERENCES workflow_execution(execution_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS workflow_event (
    event_id CHAR(36) PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    execution_id CHAR(36) NULL,
    event_type VARCHAR(64) NOT NULL,
    safe_payload_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_workflow_event_run (run_id, created_at),
    CONSTRAINT fk_workflow_event_run FOREIGN KEY (run_id) REFERENCES workflow_run(run_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS workflow_idempotency_record (
    idempotency_key VARCHAR(256) PRIMARY KEY,
    operation VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    run_id CHAR(36) NOT NULL,
    execution_id CHAR(36) NOT NULL,
    response_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    INDEX idx_workflow_idempotency_expiry (expires_at),
    CONSTRAINT fk_workflow_idempotency_run FOREIGN KEY (run_id) REFERENCES workflow_run(run_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS workflow_delivery (
    delivery_id CHAR(36) PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    execution_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    acknowledged_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_workflow_delivery_retry (status, next_attempt_at),
    CONSTRAINT fk_workflow_delivery_run FOREIGN KEY (run_id) REFERENCES workflow_run(run_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS workflow_result_temp (
    run_id CHAR(36) PRIMARY KEY,
    execution_id CHAR(36) NOT NULL,
    result_hash CHAR(64) NOT NULL,
    result_json JSON NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_workflow_result_expiry (expires_at),
    CONSTRAINT fk_workflow_result_run FOREIGN KEY (run_id) REFERENCES workflow_run(run_id) ON DELETE CASCADE
) ENGINE=InnoDB;
