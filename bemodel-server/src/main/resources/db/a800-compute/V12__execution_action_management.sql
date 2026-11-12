-- Existing action identities and receipts are retained. Legacy rows have no managed input/adapter.
ALTER TABLE execution_action
    ADD COLUMN input_json JSON,
    ADD COLUMN workflow_step VARCHAR(32),
    ADD COLUMN workflow_version BIGINT,
    ADD COLUMN executor_key VARCHAR(64),
    ADD COLUMN claim_token VARCHAR(36),
    ADD COLUMN lease_until DATETIME(6),
    ADD COLUMN retry_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN reconcile_count INT NOT NULL DEFAULT 0,
    ADD COLUMN created_by VARCHAR(64),
    ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD INDEX ix_action_reconcile (status, lease_until);

-- Preserve append-only audit entries even when a later retry changes the current action state.
DELIMITER //
CREATE TRIGGER action_identity_guard BEFORE UPDATE ON execution_action FOR EACH ROW
BEGIN
    IF NOT (NEW.workflow_run_id <=> OLD.workflow_run_id)
       OR NOT (NEW.decision_id <=> OLD.decision_id)
       OR NOT (NEW.action_type <=> OLD.action_type)
       OR NOT (NEW.target_kind <=> OLD.target_kind)
       OR NOT (NEW.target_id <=> OLD.target_id)
       OR NOT (NEW.idempotency_key <=> OLD.idempotency_key)
       OR NOT (NEW.workflow_step <=> OLD.workflow_step)
       OR NOT (NEW.workflow_version <=> OLD.workflow_version)
       OR NOT (CAST(NEW.input_json AS CHAR) <=> CAST(OLD.input_json AS CHAR))
       OR (OLD.executor_key IS NOT NULL AND NOT (NEW.executor_key <=> OLD.executor_key)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Action identity, input and bound executor are immutable';
    END IF;
END//

CREATE TRIGGER managed_action_event_update_guard BEFORE UPDATE ON resource_event FOR EACH ROW
BEGIN
    IF OLD.source='COMPUTE_ACTION' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Managed action events are append-only';
    END IF;
END//

CREATE TRIGGER managed_action_event_delete_guard BEFORE DELETE ON resource_event FOR EACH ROW
BEGIN
    IF OLD.source='COMPUTE_ACTION' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Managed action events are append-only';
    END IF;
END//
DELIMITER ;
