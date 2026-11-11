CREATE TABLE rule_evaluation_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rule_set_id BIGINT NOT NULL,
    stage VARCHAR(24) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    evaluated_at DATETIME(6) NOT NULL,
    input_source VARCHAR(16) NOT NULL DEFAULT 'CALLER',
    facts JSON NOT NULL,
    overall_result VARCHAR(20) NOT NULL,
    UNIQUE (id, rule_set_id),
    FOREIGN KEY (rule_set_id) REFERENCES rule_set(id),
    CHECK (stage IN ('ADMISSION','PLANNING','SUBMISSION','EXECUTION','CLOSURE','GLOBAL')),
    CHECK (input_source = 'CALLER'),
    CHECK (overall_result IN ('PASS','FAIL','NOT_APPLICABLE','ERROR'))
);

CREATE TABLE rule_evaluation_check (
    run_id BIGINT NOT NULL,
    rule_set_id BIGINT NOT NULL,
    rule_version_id BIGINT NOT NULL,
    definition_version INT NOT NULL,
    result VARCHAR(20) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    output JSON NOT NULL,
    PRIMARY KEY (run_id, rule_version_id),
    FOREIGN KEY (run_id, rule_set_id) REFERENCES rule_evaluation_run(id, rule_set_id),
    FOREIGN KEY (rule_set_id, rule_version_id) REFERENCES rule_set_member(rule_set_id, rule_version_id),
    CHECK (definition_version > 0),
    CHECK (result IN ('PASS','FAIL','NOT_APPLICABLE','ERROR'))
);
