CREATE TABLE compute_simulator_state (
    node_id BIGINT NOT NULL PRIMARY KEY,
    state_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (node_id) REFERENCES compute_node(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE compute_simulator_scenario (
    operation_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
    scenario_json JSON NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE compute_simulator_operation (
    operation_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
    action_id BIGINT NOT NULL UNIQUE,
    node_id BIGINT NOT NULL,
    command_json JSON NOT NULL,
    scenario_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
    attempt_count INT NOT NULL DEFAULT 1,
    manager_attempt INT NOT NULL,
    send_count INT NOT NULL DEFAULT 1,
    query_count INT NOT NULL DEFAULT 0,
    effect_count INT NOT NULL DEFAULT 0,
    ready_at DATETIME(6) NOT NULL,
    receipt JSON,
    error_code VARCHAR(64),
    retry_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (action_id) REFERENCES execution_action(id),
    FOREIGN KEY (node_id) REFERENCES compute_node(id),
    CHECK (status IN ('ACCEPTED','SUCCEEDED','FAILED')),
    CHECK (effect_count IN (0,1)),
    INDEX ix_simulator_due (node_id,status,ready_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER //
CREATE TRIGGER simulator_operation_identity_guard BEFORE UPDATE ON compute_simulator_operation FOR EACH ROW
BEGIN
    IF NOT (NEW.operation_key <=> OLD.operation_key) OR NEW.action_id<>OLD.action_id OR NEW.node_id<>OLD.node_id
       OR NOT (CAST(NEW.command_json AS CHAR) <=> CAST(OLD.command_json AS CHAR))
       OR NOT (CAST(NEW.scenario_json AS CHAR) <=> CAST(OLD.scenario_json AS CHAR))
       OR (OLD.status='SUCCEEDED' AND (NEW.status<>'SUCCEEDED' OR NEW.effect_count<>OLD.effect_count
           OR NOT (CAST(NEW.receipt AS CHAR) <=> CAST(OLD.receipt AS CHAR)))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Simulator operation identity, scenario and successful result are immutable';
    END IF;
END//
CREATE TRIGGER simulator_operation_delete_guard BEFORE DELETE ON compute_simulator_operation FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Simulator operation tombstones must be retained';
END//
CREATE TRIGGER simulator_state_delete_guard BEFORE DELETE ON compute_simulator_state FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Simulator state cannot be silently reset';
END//
DELIMITER ;
