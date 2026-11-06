CREATE TABLE compute_node (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    node_key VARCHAR(64) NOT NULL,
    hostname VARCHAR(128) NOT NULL,
    memory_capacity_bytes BIGINT NOT NULL,
    memory_reserved_bytes BIGINT NOT NULL DEFAULT 0,
    temporary_capacity_bytes BIGINT NOT NULL,
    persistent_capacity_bytes BIGINT NOT NULL,
    UNIQUE (node_key),
    CHECK (memory_capacity_bytes >= 0),
    CHECK (memory_reserved_bytes >= 0),
    CHECK (temporary_capacity_bytes >= 0),
    CHECK (persistent_capacity_bytes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE gpu_group (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    node_id BIGINT NOT NULL,
    group_code VARCHAR(32) NOT NULL,
    current_hold_id BIGINT,
    pending_allocation_id BIGINT,
    UNIQUE (node_id, group_code),
    UNIQUE (id, node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE gpu_device (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    node_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    gpu_uuid VARCHAR(80) NOT NULL,
    gpu_index SMALLINT NOT NULL,
    model VARCHAR(64) NOT NULL,
    vram_bytes BIGINT NOT NULL,
    UNIQUE (gpu_uuid),
    UNIQUE (node_id, gpu_index),
    CHECK (vram_bytes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE deployment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    owner_employee_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    preemptible BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(24) NOT NULL DEFAULT 'REGISTERED',
    active_group_id BIGINT GENERATED ALWAYS AS (CASE WHEN status <> 'RETIRED' THEN group_id END) STORED,
    current_revision_id BIGINT,
    UNIQUE (active_group_id),
    UNIQUE (id, group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE deployment_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    deployment_id BIGINT NOT NULL,
    version INT NOT NULL,
    image_ref VARCHAR(512) NOT NULL,
    command_args JSON NOT NULL,
    mounts JSON NOT NULL,
    health_check JSON NOT NULL,
    memory_bytes BIGINT NOT NULL,
    temporary_bytes BIGINT NOT NULL DEFAULT 0,
    persistent_bytes BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE (deployment_id, version),
    UNIQUE (id, deployment_id),
    CHECK (memory_bytes >= 0),
    CHECK (temporary_bytes >= 0),
    CHECK (persistent_bytes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resource_observation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    node_id BIGINT NOT NULL,
    source_subject_kind VARCHAR(24) NOT NULL,
    source_subject_key VARCHAR(255) NOT NULL,
    subject_kind VARCHAR(24),
    subject_id BIGINT,
    source VARCHAR(128) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    valid_until DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL,
    values_json JSON,
    error_detail VARCHAR(512),
    INDEX ix_observation_subject (subject_kind, subject_id, observed_at),
    INDEX ix_observation_source (node_id, source, source_subject_kind, source_subject_key, observed_at),
    CHECK ((subject_kind IS NULL) = (subject_id IS NULL)),
    CHECK (valid_until >= observed_at),
    CHECK (status IN ('OK','ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE team (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    UNIQUE (team_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE employee (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    employee_code VARCHAR(64) NOT NULL,
    team_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    email VARCHAR(254),
    UNIQUE (employee_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    owner_employee_id BIGINT NOT NULL,
    cost_center VARCHAR(64),
    UNIQUE (project_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_team (
    project_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    PRIMARY KEY (project_id, team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_type (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    priority SMALLINT NOT NULL,
    default_resource_spec JSON NOT NULL,
    UNIQUE (type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE compute_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    employee_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    task_type_id BIGINT NOT NULL,
    client_request_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    requested_gpu_count SMALLINT NOT NULL,
    requested_resource_spec JSON NOT NULL,
    launch_spec JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED',
    submitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE (employee_id, client_request_key),
    CHECK (requested_gpu_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE compute_job (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    task_type_id BIGINT NOT NULL,
    effective_gpu_count SMALLINT NOT NULL,
    effective_resource_spec JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6),
    ended_at DATETIME(6),
    runtime_ref VARCHAR(255),
    UNIQUE (id, request_id),
    CHECK (effective_gpu_count BETWEEN 1 AND 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE active_job (
    request_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    PRIMARY KEY (request_id),
    UNIQUE (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE gpu_allocation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    pending_group_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN group_id END) STORED,
    memory_bytes BIGINT NOT NULL,
    temporary_bytes BIGINT NOT NULL DEFAULT 0,
    persistent_bytes BIGINT NOT NULL DEFAULT 0,
    reserved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    activated_at DATETIME(6),
    closed_at DATETIME(6),
    close_reason VARCHAR(64),
    UNIQUE (job_id),
    UNIQUE (id, group_id),
    UNIQUE (pending_group_id),
    CHECK (status IN ('PENDING','OCCUPIED','RELEASED','CANCELLED')),
    CHECK (memory_bytes >= 0),
    CHECK (temporary_bytes >= 0),
    CHECK (persistent_bytes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE allocation_gpu (
    allocation_id BIGINT NOT NULL,
    gpu_id BIGINT NOT NULL,
    PRIMARY KEY (allocation_id, gpu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE hold_period (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    deployment_id BIGINT,
    allocation_id BIGINT,
    deployment_revision_id BIGINT,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6),
    open_group_id BIGINT GENERATED ALWAYS AS (CASE WHEN ended_at IS NULL THEN group_id END) STORED,
    UNIQUE (id, group_id),
    UNIQUE (open_group_id),
    CHECK ((deployment_id IS NULL) <> (allocation_id IS NULL)),
    CHECK ((deployment_id IS NULL) = (deployment_revision_id IS NULL)),
    CHECK (ended_at IS NULL OR ended_at >= started_at),
    INDEX ix_hold_history (group_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE compute_queue (
    request_id BIGINT NOT NULL,
    target_group_id BIGINT,
    priority SMALLINT NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    reason_code VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (request_id),
    INDEX ix_queue_order (target_group_id, status, priority DESC, submitted_at, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rule_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rule_code VARCHAR(32) NOT NULL,
    version INT NOT NULL,
    source_rule_id BIGINT,
    source_release_id BIGINT,
    evaluator_version VARCHAR(64) NOT NULL,
    stage VARCHAR(24) NOT NULL,
    rule_type VARCHAR(24) NOT NULL,
    owner_employee_id BIGINT NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_until DATETIME(6),
    definition JSON NOT NULL,
    reason_codes JSON NOT NULL,
    UNIQUE (rule_code, version),
    CHECK (effective_until IS NULL OR effective_until > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    as_of DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE (id, as_of)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE decision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64),
    as_of DATETIME(6) NOT NULL,
    snapshot_id BIGINT NOT NULL,
    target_kind VARCHAR(24),
    target_id BIGINT,
    plan JSON,
    previous_decision_id BIGINT,
    supersedes_decision_id BIGINT,
    UNIQUE (id, request_id, kind),
    INDEX ix_decision_history (request_id, kind, as_of),
    CHECK ((target_kind IS NULL) = (target_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE current_decision (
    request_id BIGINT NOT NULL,
    decision_kind VARCHAR(16) NOT NULL,
    decision_id BIGINT NOT NULL,
    PRIMARY KEY (request_id, decision_kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rule_check (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    decision_id BIGINT NOT NULL,
    rule_version_id BIGINT NOT NULL,
    result VARCHAR(20) NOT NULL,
    output JSON,
    reason_code VARCHAR(64),
    checked_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CHECK (result IN ('PASS','FAIL','NOT_APPLICABLE','ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    source VARCHAR(128) NOT NULL,
    source_record_ref VARCHAR(128),
    observation_id BIGINT,
    observed_at DATETIME(6) NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    values_json JSON NOT NULL,
    CHECK (valid_until >= observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rule_check_evidence (
    rule_check_id BIGINT NOT NULL,
    evidence_id BIGINT NOT NULL,
    PRIMARY KEY (rule_check_id, evidence_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    decision_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    restore_revision_id BIGINT,
    step VARCHAR(32) NOT NULL DEFAULT 'RESERVED',
    status VARCHAR(24) NOT NULL DEFAULT 'READY',
    row_version BIGINT NOT NULL DEFAULT 0,
    next_run_at DATETIME(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_workflow_due (status, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE execution_action (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    workflow_run_id BIGINT NOT NULL,
    decision_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    target_kind VARCHAR(24) NOT NULL,
    target_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    started_at DATETIME(6),
    finished_at DATETIME(6),
    receipt JSON,
    error_code VARCHAR(64),
    UNIQUE (idempotency_key),
    INDEX ix_action_flow (workflow_run_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resource_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source VARCHAR(128) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    subject_kind VARCHAR(24) NOT NULL,
    subject_id BIGINT NOT NULL,
    causing_decision_id BIGINT,
    action_id BIGINT,
    occurred_at DATETIME(6) NOT NULL,
    impact_scope JSON NOT NULL,
    payload JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE gpu_group
    ADD FOREIGN KEY (node_id) REFERENCES compute_node (id),
    ADD FOREIGN KEY (current_hold_id,id) REFERENCES hold_period (id,group_id),
    ADD FOREIGN KEY (pending_allocation_id,id) REFERENCES gpu_allocation (id,group_id);

ALTER TABLE gpu_device
    ADD FOREIGN KEY (group_id,node_id) REFERENCES gpu_group (id,node_id);

ALTER TABLE deployment
    ADD FOREIGN KEY (group_id) REFERENCES gpu_group (id),
    ADD FOREIGN KEY (project_id) REFERENCES project (id),
    ADD FOREIGN KEY (owner_employee_id) REFERENCES employee (id),
    ADD FOREIGN KEY (current_revision_id,id) REFERENCES deployment_revision (id,deployment_id);

ALTER TABLE deployment_revision
    ADD FOREIGN KEY (deployment_id) REFERENCES deployment (id);

ALTER TABLE resource_observation
    ADD FOREIGN KEY (node_id) REFERENCES compute_node (id);

ALTER TABLE employee
    ADD FOREIGN KEY (team_id) REFERENCES team (id);

ALTER TABLE project
    ADD FOREIGN KEY (owner_employee_id) REFERENCES employee (id);

ALTER TABLE project_team
    ADD FOREIGN KEY (project_id) REFERENCES project (id),
    ADD FOREIGN KEY (team_id) REFERENCES team (id);

ALTER TABLE compute_request
    ADD FOREIGN KEY (employee_id) REFERENCES employee (id),
    ADD FOREIGN KEY (project_id) REFERENCES project (id),
    ADD FOREIGN KEY (task_type_id) REFERENCES task_type (id);

ALTER TABLE compute_job
    ADD FOREIGN KEY (request_id) REFERENCES compute_request (id),
    ADD FOREIGN KEY (task_type_id) REFERENCES task_type (id);

ALTER TABLE active_job
    ADD FOREIGN KEY (job_id,request_id) REFERENCES compute_job (id,request_id);

ALTER TABLE gpu_allocation
    ADD FOREIGN KEY (job_id) REFERENCES compute_job (id),
    ADD FOREIGN KEY (group_id) REFERENCES gpu_group (id);

ALTER TABLE allocation_gpu
    ADD FOREIGN KEY (allocation_id) REFERENCES gpu_allocation (id),
    ADD FOREIGN KEY (gpu_id) REFERENCES gpu_device (id);

ALTER TABLE hold_period
    ADD FOREIGN KEY (group_id) REFERENCES gpu_group (id),
    ADD FOREIGN KEY (deployment_id,group_id) REFERENCES deployment (id,group_id),
    ADD FOREIGN KEY (allocation_id,group_id) REFERENCES gpu_allocation (id,group_id),
    ADD FOREIGN KEY (deployment_revision_id,deployment_id) REFERENCES deployment_revision (id,deployment_id);

ALTER TABLE compute_queue
    ADD FOREIGN KEY (request_id) REFERENCES compute_request (id),
    ADD FOREIGN KEY (target_group_id) REFERENCES gpu_group (id);

ALTER TABLE rule_version
    ADD FOREIGN KEY (owner_employee_id) REFERENCES employee (id);

ALTER TABLE decision
    ADD FOREIGN KEY (request_id) REFERENCES compute_request (id),
    ADD FOREIGN KEY (snapshot_id,as_of) REFERENCES evidence_snapshot (id,as_of),
    ADD FOREIGN KEY (previous_decision_id) REFERENCES decision (id),
    ADD FOREIGN KEY (supersedes_decision_id,request_id,kind) REFERENCES decision (id,request_id,kind);

ALTER TABLE current_decision
    ADD FOREIGN KEY (decision_id,request_id,decision_kind) REFERENCES decision (id,request_id,kind);

ALTER TABLE rule_check
    ADD FOREIGN KEY (decision_id) REFERENCES decision (id),
    ADD FOREIGN KEY (rule_version_id) REFERENCES rule_version (id);

ALTER TABLE evidence
    ADD FOREIGN KEY (snapshot_id) REFERENCES evidence_snapshot (id),
    ADD FOREIGN KEY (observation_id) REFERENCES resource_observation (id);

ALTER TABLE rule_check_evidence
    ADD FOREIGN KEY (rule_check_id) REFERENCES rule_check (id),
    ADD FOREIGN KEY (evidence_id) REFERENCES evidence (id);

ALTER TABLE workflow_run
    ADD FOREIGN KEY (request_id) REFERENCES compute_request (id),
    ADD FOREIGN KEY (decision_id) REFERENCES decision (id),
    ADD FOREIGN KEY (group_id) REFERENCES gpu_group (id),
    ADD FOREIGN KEY (job_id,request_id) REFERENCES compute_job (id,request_id),
    ADD FOREIGN KEY (restore_revision_id) REFERENCES deployment_revision (id);

ALTER TABLE execution_action
    ADD FOREIGN KEY (workflow_run_id) REFERENCES workflow_run (id),
    ADD FOREIGN KEY (decision_id) REFERENCES decision (id);

ALTER TABLE resource_event
    ADD FOREIGN KEY (causing_decision_id) REFERENCES decision (id),
    ADD FOREIGN KEY (action_id) REFERENCES execution_action (id);
