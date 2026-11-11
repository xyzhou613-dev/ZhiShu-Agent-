ALTER TABLE evidence_snapshot ADD UNIQUE (id, rule_set_id, node_id, request_id);

CREATE TABLE target_group_selection (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    rule_set_id BIGINT NOT NULL,
    rule_version_id BIGINT NOT NULL,
    definition_version INT NOT NULL,
    node_id BIGINT NOT NULL,
    request_id BIGINT NOT NULL,
    job_id BIGINT NULL,
    gpu_count SMALLINT NOT NULL,
    spec_basis VARCHAR(16) NOT NULL,
    target_group_id BIGINT NULL,
    target_group_code VARCHAR(32) NULL,
    rule_result VARCHAR(20) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    selection_status VARCHAR(40) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    facts JSON NOT NULL,
    output JSON NOT NULL,
    UNIQUE (snapshot_id),
    UNIQUE (id, snapshot_id),
    INDEX ix_target_selection_request (request_id, id),
    FOREIGN KEY (snapshot_id, rule_set_id, node_id, request_id) REFERENCES evidence_snapshot(id, rule_set_id, node_id, request_id),
    FOREIGN KEY (rule_set_id, rule_version_id) REFERENCES rule_set_member(rule_set_id, rule_version_id),
    FOREIGN KEY (target_group_id, node_id) REFERENCES gpu_group(id, node_id),
    FOREIGN KEY (job_id, request_id) REFERENCES compute_job(id, request_id),
    CHECK (gpu_count > 0 AND definition_version > 0),
    CHECK ((spec_basis='REQUEST' AND job_id IS NULL) OR (spec_basis='JOB' AND job_id IS NOT NULL)),
    CHECK ((target_group_id IS NULL) = (target_group_code IS NULL)),
    CHECK (rule_result IN ('PASS','FAIL','NOT_APPLICABLE','ERROR')),
    CHECK (rule_result <> 'PASS' OR target_group_id IS NOT NULL),
    CHECK (selection_status IN ('TARGET_SELECTED','WAITING','PREEMPTION_CHECK_REQUIRED'))
);

CREATE TABLE target_group_selection_evidence (
    selection_id BIGINT NOT NULL,
    snapshot_id BIGINT NOT NULL,
    evidence_id BIGINT NOT NULL,
    PRIMARY KEY (selection_id, evidence_id),
    FOREIGN KEY (selection_id, snapshot_id) REFERENCES target_group_selection(id, snapshot_id),
    FOREIGN KEY (evidence_id, snapshot_id) REFERENCES evidence(id, snapshot_id)
);
