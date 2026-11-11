CREATE TABLE rule_set (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_release_id BIGINT NOT NULL UNIQUE,
    source_version_tag VARCHAR(32) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    evaluator_version VARCHAR(64) NOT NULL,
    formula_version VARCHAR(64) NOT NULL,
    settings JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE rule_set_member (
    rule_set_id BIGINT NOT NULL,
    rule_code VARCHAR(32) NOT NULL,
    rule_version_id BIGINT NOT NULL,
    PRIMARY KEY(rule_set_id,rule_code),
    UNIQUE(rule_set_id,rule_version_id),
    FOREIGN KEY(rule_set_id) REFERENCES rule_set(id),
    FOREIGN KEY(rule_version_id) REFERENCES rule_version(id)
);
CREATE TABLE active_rule_set (
    id INT PRIMARY KEY,
    rule_set_id BIGINT,
    FOREIGN KEY(rule_set_id) REFERENCES rule_set(id),
    CHECK(id=1)
);
INSERT INTO active_rule_set VALUES(1,NULL);
CREATE TABLE rule_activation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_set_id BIGINT NOT NULL UNIQUE,
    previous_set_id BIGINT,
    actor VARCHAR(64) NOT NULL,
    activated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY(rule_set_id) REFERENCES rule_set(id),
    FOREIGN KEY(previous_set_id) REFERENCES rule_set(id)
);
DELIMITER $$
CREATE TRIGGER trg_rule_version_update BEFORE UPDATE ON rule_version FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Rule execution snapshot is immutable';
END$$
CREATE TRIGGER trg_rule_version_delete BEFORE DELETE ON rule_version FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Rule execution snapshot cannot be deleted';
END$$
CREATE TRIGGER trg_rule_set_update BEFORE UPDATE ON rule_set FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Rule set snapshot is immutable';
END$$
CREATE TRIGGER trg_rule_set_delete BEFORE DELETE ON rule_set FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Rule set snapshot cannot be deleted';
END$$
DELIMITER ;
