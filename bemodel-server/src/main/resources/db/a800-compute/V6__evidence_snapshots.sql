ALTER TABLE evidence_snapshot
    ADD COLUMN rule_set_id BIGINT NULL,
    ADD COLUMN node_id BIGINT NULL,
    ADD COLUMN request_id BIGINT NULL,
    ADD COLUMN actor VARCHAR(64) NULL,
    ADD COLUMN manifest JSON NULL,
    ADD COLUMN sealed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD UNIQUE (id, rule_set_id),
    ADD FOREIGN KEY (rule_set_id) REFERENCES rule_set(id),
    ADD FOREIGN KEY (node_id) REFERENCES compute_node(id),
    ADD FOREIGN KEY (request_id) REFERENCES compute_request(id);

ALTER TABLE evidence ADD UNIQUE (id, snapshot_id);

DELIMITER //
CREATE TRIGGER evidence_snapshot_update_guard BEFORE UPDATE ON evidence_snapshot FOR EACH ROW
BEGIN
    IF OLD.sealed OR NOT NEW.sealed
       OR NOT (OLD.id <=> NEW.id) OR NOT (OLD.as_of <=> NEW.as_of)
       OR NOT (OLD.created_at <=> NEW.created_at) OR NOT (OLD.rule_set_id <=> NEW.rule_set_id)
       OR NOT (OLD.node_id <=> NEW.node_id) OR NOT (OLD.request_id <=> NEW.request_id)
       OR NOT (OLD.actor <=> NEW.actor) OR NOT (OLD.manifest <=> NEW.manifest) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Evidence snapshots are immutable; only sealing is allowed';
    END IF;
END//
CREATE TRIGGER evidence_snapshot_delete_guard BEFORE DELETE ON evidence_snapshot FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Evidence snapshots cannot be deleted';
END//
CREATE TRIGGER evidence_insert_guard BEFORE INSERT ON evidence FOR EACH ROW
BEGIN
    DECLARE snapshot_sealed BOOLEAN;
    SELECT sealed INTO snapshot_sealed FROM evidence_snapshot WHERE id=NEW.snapshot_id FOR UPDATE;
    IF snapshot_sealed THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed snapshots cannot accept more evidence';
    END IF;
END//
CREATE TRIGGER evidence_update_guard BEFORE UPDATE ON evidence FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Evidence values and timestamps are immutable';
END//
CREATE TRIGGER evidence_delete_guard BEFORE DELETE ON evidence FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Evidence cannot be deleted';
END//
DELIMITER ;
