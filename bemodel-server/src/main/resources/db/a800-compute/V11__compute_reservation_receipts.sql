ALTER TABLE gpu_allocation ADD UNIQUE(id,job_id,group_id);
ALTER TABLE decision ADD COLUMN rule_set_id BIGINT NULL,
 ADD FOREIGN KEY(rule_set_id) REFERENCES rule_set(id),
 ADD FOREIGN KEY(snapshot_id,rule_set_id) REFERENCES evidence_snapshot(id,rule_set_id),
 ADD UNIQUE(id,rule_set_id);
ALTER TABLE rule_check ADD COLUMN rule_set_id BIGINT NULL,
 ADD COLUMN definition_version INT NULL, ADD COLUMN facts JSON NULL,
 ADD FOREIGN KEY(decision_id,rule_set_id) REFERENCES decision(id,rule_set_id),
 ADD FOREIGN KEY(rule_set_id,rule_version_id) REFERENCES rule_set_member(rule_set_id,rule_version_id);
CREATE TABLE compute_reservation_receipt (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id BIGINT NOT NULL,
 job_id BIGINT NOT NULL,
 allocation_id BIGINT NOT NULL,
 group_id BIGINT NOT NULL,
 node_id BIGINT NOT NULL,
 rule_set_id BIGINT NOT NULL,
 snapshot_id BIGINT NOT NULL,
 projection_run_id BIGINT NOT NULL,
 admission_decision_id BIGINT NOT NULL,
 submission_decision_id BIGINT NOT NULL,
 admission_kind VARCHAR(16) NOT NULL DEFAULT 'ADMISSION',
 submission_kind VARCHAR(16) NOT NULL DEFAULT 'SUBMISSION',
 restore_revision_id BIGINT NULL,
 actor VARCHAR(64) NOT NULL,
 UNIQUE(allocation_id), UNIQUE(job_id),
 INDEX ix_reservation_request(request_id,id),
 FOREIGN KEY(allocation_id,job_id,group_id) REFERENCES gpu_allocation(id,job_id,group_id),
 FOREIGN KEY(job_id,request_id) REFERENCES compute_job(id,request_id),
 FOREIGN KEY(group_id,node_id) REFERENCES gpu_group(id,node_id),
 FOREIGN KEY(snapshot_id,rule_set_id,node_id,request_id) REFERENCES evidence_snapshot(id,rule_set_id,node_id,request_id),
 FOREIGN KEY(projection_run_id,snapshot_id,rule_set_id) REFERENCES capacity_projection_run(id,snapshot_id,rule_set_id),
 FOREIGN KEY(admission_decision_id,request_id,admission_kind) REFERENCES decision(id,request_id,kind),
 FOREIGN KEY(submission_decision_id,request_id,submission_kind) REFERENCES decision(id,request_id,kind),
 FOREIGN KEY(admission_decision_id,rule_set_id) REFERENCES decision(id,rule_set_id),
 FOREIGN KEY(submission_decision_id,rule_set_id) REFERENCES decision(id,rule_set_id),
 FOREIGN KEY(restore_revision_id) REFERENCES deployment_revision(id),
 CHECK(admission_kind='ADMISSION' AND submission_kind='SUBMISSION')
);
DELIMITER //
CREATE TRIGGER rule_check_evidence_snapshot_guard BEFORE INSERT ON rule_check_evidence FOR EACH ROW
BEGIN
 IF NOT EXISTS(SELECT 1 FROM rule_check c JOIN decision d ON d.id=c.decision_id JOIN evidence e ON e.id=NEW.evidence_id
               WHERE c.id=NEW.rule_check_id AND d.snapshot_id=e.snapshot_id) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Rule check evidence must belong to the decision snapshot';
 END IF;
END//
DELIMITER ;
