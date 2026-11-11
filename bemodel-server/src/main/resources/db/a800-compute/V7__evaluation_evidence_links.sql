-- V5 creates three unnamed checks; MySQL names the input_source check *_chk_2.
ALTER TABLE rule_evaluation_run
    DROP CHECK rule_evaluation_run_chk_2,
    ADD COLUMN snapshot_id BIGINT NULL,
    ADD UNIQUE (id, snapshot_id),
    ADD FOREIGN KEY (snapshot_id, rule_set_id) REFERENCES evidence_snapshot(id, rule_set_id),
    ADD CONSTRAINT ck_evaluation_input_source CHECK (
        (input_source='CALLER' AND snapshot_id IS NULL)
        OR (input_source='SERVER_SNAPSHOT' AND snapshot_id IS NOT NULL AND stage='ADMISSION')
    );

CREATE TABLE rule_evaluation_check_evidence (
    run_id BIGINT NOT NULL,
    rule_version_id BIGINT NOT NULL,
    evidence_id BIGINT NOT NULL,
    snapshot_id BIGINT NOT NULL,
    PRIMARY KEY (run_id, rule_version_id, evidence_id),
    FOREIGN KEY (run_id, rule_version_id) REFERENCES rule_evaluation_check(run_id, rule_version_id),
    FOREIGN KEY (run_id, snapshot_id) REFERENCES rule_evaluation_run(id, snapshot_id),
    FOREIGN KEY (evidence_id, snapshot_id) REFERENCES evidence(id, snapshot_id)
);
