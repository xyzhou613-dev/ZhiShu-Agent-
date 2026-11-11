CREATE TABLE capacity_projection_run (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 snapshot_id BIGINT NOT NULL,
 rule_set_id BIGINT NOT NULL,
 node_id BIGINT NOT NULL,
 request_id BIGINT NOT NULL,
 mode VARCHAR(16) NOT NULL,
 target_group_id BIGINT NULL,
 target_group_code VARCHAR(32) NULL,
 overall_result VARCHAR(20) NOT NULL,
 feasibility VARCHAR(16) NOT NULL,
 actor VARCHAR(64) NOT NULL,
 proposal JSON NOT NULL,
 output JSON NOT NULL,
 UNIQUE(snapshot_id),
 UNIQUE(id,snapshot_id,rule_set_id),
 UNIQUE(id,snapshot_id),
 INDEX ix_projection_request(request_id,id),
 FOREIGN KEY(snapshot_id,rule_set_id,node_id,request_id) REFERENCES evidence_snapshot(id,rule_set_id,node_id,request_id),
 FOREIGN KEY(target_group_id,node_id) REFERENCES gpu_group(id,node_id),
 CHECK(mode IN ('PLAN','RESTORE')),
 CHECK(overall_result IN ('PASS','FAIL','NOT_APPLICABLE','ERROR')),
 CHECK(feasibility IN ('FEASIBLE','INFEASIBLE','UNKNOWN')),
 CHECK((target_group_id IS NULL)=(target_group_code IS NULL))
);
CREATE TABLE capacity_projection_check (
 run_id BIGINT NOT NULL,
 snapshot_id BIGINT NOT NULL,
 rule_set_id BIGINT NOT NULL,
 rule_version_id BIGINT NOT NULL,
 definition_version INT NOT NULL,
 result VARCHAR(20) NOT NULL,
 reason_code VARCHAR(64) NOT NULL,
 facts JSON NOT NULL,
 output JSON NOT NULL,
 PRIMARY KEY(run_id,rule_version_id),
 FOREIGN KEY(run_id,snapshot_id,rule_set_id) REFERENCES capacity_projection_run(id,snapshot_id,rule_set_id),
 FOREIGN KEY(rule_set_id,rule_version_id) REFERENCES rule_set_member(rule_set_id,rule_version_id),
 CHECK(definition_version>0),
 CHECK(result IN ('PASS','FAIL','NOT_APPLICABLE','ERROR'))
);
CREATE TABLE capacity_projection_evidence (
 run_id BIGINT NOT NULL,
 snapshot_id BIGINT NOT NULL,
 evidence_id BIGINT NOT NULL,
 PRIMARY KEY(run_id,evidence_id),
 FOREIGN KEY(run_id,snapshot_id) REFERENCES capacity_projection_run(id,snapshot_id),
 FOREIGN KEY(evidence_id,snapshot_id) REFERENCES evidence(id,snapshot_id)
);
