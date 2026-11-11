CREATE TABLE bm_compute_publish_lock (id INT PRIMARY KEY);
INSERT INTO bm_compute_publish_lock VALUES(1);
CREATE TABLE bm_compute_release_sync (
    release_id BIGINT PRIMARY KEY,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'STAGED',
    attempt_count INT NOT NULL DEFAULT 0,
    active_set_id BIGINT,
    last_error VARCHAR(512),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY(release_id) REFERENCES bm_release(id),
    CHECK(status IN ('STAGED','SYNCED','FAILED'))
);
CREATE TABLE bm_compute_rule_conflict (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_codes JSON NOT NULL,
    description VARCHAR(512) NOT NULL,
    priority VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolution VARCHAR(512),
    resolved_by VARCHAR(64),
    resolved_at DATETIME(6),
    CHECK(status IN ('OPEN','RESOLVED'))
);
CREATE TABLE bm_compute_operation_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor VARCHAR(64) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    subject_ref VARCHAR(128) NOT NULL,
    detail JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
