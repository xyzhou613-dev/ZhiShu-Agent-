-- Protect history even for writes bypassing Java. No existing business rows are changed.
DELIMITER $$
CREATE TRIGGER trg_hold_update BEFORE UPDATE ON hold_period FOR EACH ROW
BEGIN
    IF OLD.ended_at IS NOT NULL
       OR NOT (NEW.id <=> OLD.id) OR NOT (NEW.group_id <=> OLD.group_id)
       OR NOT (NEW.deployment_id <=> OLD.deployment_id) OR NOT (NEW.allocation_id <=> OLD.allocation_id)
       OR NOT (NEW.deployment_revision_id <=> OLD.deployment_revision_id)
       OR NOT (NEW.started_at <=> OLD.started_at) OR NEW.ended_at IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I-23: hold is immutable except one-time close';
    END IF;
END$$
CREATE TRIGGER trg_hold_delete BEFORE DELETE ON hold_period FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I-23: hold history cannot be deleted';
END$$
CREATE TRIGGER trg_hold_insert BEFORE INSERT ON hold_period FOR EACH ROW
BEGIN
    DECLARE locked_group BIGINT;
    SELECT id INTO locked_group FROM gpu_group WHERE id=NEW.group_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM hold_period h WHERE h.group_id=NEW.group_id
               AND (NEW.ended_at IS NULL OR h.started_at < NEW.ended_at)
               AND (h.ended_at IS NULL OR h.ended_at > NEW.started_at)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I-5: overlapping hold interval';
    END IF;
    IF NEW.allocation_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM gpu_allocation a WHERE a.id=NEW.allocation_id AND a.group_id=NEW.group_id AND a.status='OCCUPIED'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I-21: only occupied allocation may hold group';
    END IF;
END$$
CREATE TRIGGER trg_allocation_gpu_insert BEFORE INSERT ON allocation_gpu FOR EACH ROW
BEGIN
    IF NOT EXISTS (SELECT 1 FROM gpu_allocation a JOIN gpu_device g ON g.group_id=a.group_id
                   WHERE a.id=NEW.allocation_id AND g.id=NEW.gpu_id AND a.status='PENDING') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I-11: GPU must belong to pending allocation group';
    END IF;
END$$
CREATE TRIGGER trg_allocation_gpu_update BEFORE UPDATE ON allocation_gpu FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I-11: GPU membership cannot be rewritten';
END$$
CREATE TRIGGER trg_allocation_gpu_delete BEFORE DELETE ON allocation_gpu FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM gpu_allocation WHERE id=OLD.allocation_id AND status<>'PENDING') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I-11: activated GPU membership is historical';
    END IF;
END$$
CREATE TRIGGER trg_allocation_insert BEFORE INSERT ON gpu_allocation FOR EACH ROW
BEGIN
    IF NEW.status='OCCUPIED' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I-11: create pending allocation before activation';
    END IF;
END$$
CREATE TRIGGER trg_allocation_activate BEFORE UPDATE ON gpu_allocation FOR EACH ROW
BEGIN
    IF NOT (NEW.job_id <=> OLD.job_id) OR NOT (NEW.group_id <=> OLD.group_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I-2: allocation ownership is immutable';
    END IF;
    IF NEW.status='OCCUPIED' AND OLD.status<>'OCCUPIED' THEN
        IF OLD.status<>'PENDING' OR (SELECT COUNT(*) FROM allocation_gpu WHERE allocation_id=OLD.id)
            <> (SELECT effective_gpu_count FROM compute_job WHERE id=OLD.job_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I-11: activation requires exact GPU count';
        END IF;
    END IF;
END$$
CREATE TRIGGER trg_job_type_insert BEFORE INSERT ON compute_job FOR EACH ROW
BEGIN
    IF NOT EXISTS (SELECT 1 FROM compute_request WHERE id=NEW.request_id AND task_type_id=NEW.task_type_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I-8: task reassignment requires a future controlled decision API';
    END IF;
END$$
CREATE TRIGGER trg_job_type_update BEFORE UPDATE ON compute_job FOR EACH ROW
BEGIN
    IF NOT (NEW.request_id <=> OLD.request_id) OR NOT (NEW.task_type_id <=> OLD.task_type_id)
       OR NOT (NEW.effective_gpu_count <=> OLD.effective_gpu_count) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I-8: create a new job instead of rewriting task identity';
    END IF;
END$$
DELIMITER ;
