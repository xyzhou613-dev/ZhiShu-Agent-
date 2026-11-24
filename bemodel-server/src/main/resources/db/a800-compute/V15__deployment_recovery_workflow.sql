-- Prospective declarations, never backfill missing historical GPU requirements.
CREATE TABLE deployment_recovery_spec (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 revision_id BIGINT NOT NULL,
 vram_bytes_per_gpu BIGINT NOT NULL,
 gpu_interconnect VARCHAR(32) NOT NULL,
 harness_team_id BIGINT NOT NULL,
 declaration VARCHAR(512) NOT NULL,
 declared_by VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(revision_id) REFERENCES deployment_revision(id),
 FOREIGN KEY(harness_team_id) REFERENCES team(id),
 CHECK(vram_bytes_per_gpu>0), INDEX ix_recovery_revision(revision_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE workflow_run
 ADD original_deployment_id BIGINT,
 ADD original_hold_id BIGINT,
 ADD restore_spec_id BIGINT,
 ADD recovery_state VARCHAR(24),
 ADD last_closure_decision_id BIGINT,
 ADD notification_recipients JSON,
 ADD FOREIGN KEY(original_deployment_id) REFERENCES deployment(id),
 ADD FOREIGN KEY(original_hold_id) REFERENCES hold_period(id),
 ADD FOREIGN KEY(restore_spec_id) REFERENCES deployment_recovery_spec(id);
ALTER TABLE workflow_run ADD FOREIGN KEY(last_closure_decision_id) REFERENCES decision(id);
CREATE TABLE compute_restore_reservation (
 workflow_id BIGINT NOT NULL PRIMARY KEY,
 node_id BIGINT NOT NULL,
 group_id BIGINT NOT NULL UNIQUE,
 revision_id BIGINT NOT NULL,
 memory_bytes BIGINT NOT NULL,
 temporary_bytes BIGINT NOT NULL,
 persistent_bytes BIGINT NOT NULL,
 FOREIGN KEY(workflow_id) REFERENCES workflow_run(id),
 FOREIGN KEY(node_id) REFERENCES compute_node(id),
 FOREIGN KEY(group_id,node_id) REFERENCES gpu_group(id,node_id),
 FOREIGN KEY(revision_id) REFERENCES deployment_revision(id),
 CHECK(memory_bytes>=0 AND temporary_bytes>=0 AND persistent_bytes>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DELIMITER //
CREATE TRIGGER recovery_spec_update_guard BEFORE UPDATE ON deployment_recovery_spec FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Recovery declarations are immutable; append a new declaration'; END//
CREATE TRIGGER recovery_spec_delete_guard BEFORE DELETE ON deployment_recovery_spec FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Recovery declarations are historical'; END//
CREATE TRIGGER deployment_revision_update_guard BEFORE UPDATE ON deployment_revision FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Deployment revisions are immutable; append a new revision'; END//
CREATE TRIGGER deployment_revision_delete_guard BEFORE DELETE ON deployment_revision FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Deployment revisions are historical'; END//
CREATE TRIGGER workflow_recovery_identity_guard BEFORE UPDATE ON workflow_run FOR EACH ROW
BEGIN
 IF OLD.original_deployment_id IS NOT NULL AND (
 NOT(NEW.original_deployment_id <=> OLD.original_deployment_id) OR NOT(NEW.original_hold_id <=> OLD.original_hold_id)
 OR NOT(NEW.restore_revision_id <=> OLD.restore_revision_id) OR NOT(NEW.restore_spec_id <=> OLD.restore_spec_id)
 OR NOT(CAST(NEW.notification_recipients AS CHAR) <=> CAST(OLD.notification_recipients AS CHAR))) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Workflow recovery responsibility is immutable'; END IF;
END//
DELIMITER ;
