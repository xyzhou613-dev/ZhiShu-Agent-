-- =============================================================
-- V10: 护士站产品线 —— 医嘱执行确认（住院闭环最后一环）
-- 执行确认是「已执行」状态的真实业务来源：给药/采样/治疗由护士确认
-- =============================================================

CREATE DATABASE IF NOT EXISTS demo_nurse DEFAULT CHARSET utf8mb4;

CREATE TABLE IF NOT EXISTS demo_nurse.nurse_exec (
    exec_id    VARCHAR(32) PRIMARY KEY COMMENT '执行确认号',
    order_id   VARCHAR(32) COMMENT 'HIS医嘱号',
    inhos_no   VARCHAR(32) COMMENT '住院号',
    exec_type  VARCHAR(16) COMMENT '给药/采样/检查陪送/治疗',
    exec_time  DATETIME COMMENT '执行确认时间',
    nurse      VARCHAR(64) COMMENT '执行护士',
    exec_status VARCHAR(4) COMMENT '1已执行 0未执行',
    KEY idx_order (order_id),
    KEY idx_inhos (inhos_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='护士执行确认';

-- ---------- 本体扩展：执行确认 ----------
INSERT INTO bm_concept (code, name, domain_code, definition, owner, status, version) VALUES
('NURSE_EXEC', '执行确认', 'CLINICAL', '护士站对医嘱的执行确认（给药/采样/检查陪送/治疗），是医嘱「已执行」状态的真实业务来源。', '李临床', 'PUBLISHED', 1);

INSERT INTO bm_attribute (concept_code, attr_code, attr_name, data_type, is_key, definition, sort) VALUES
('NURSE_EXEC', 'exec_id', '执行确认号', 'STRING', 1, NULL, 1),
('NURSE_EXEC', 'exec_type', '执行类型', 'ENUM', 0, '给药/采样/检查陪送/治疗', 2),
('NURSE_EXEC', 'exec_time', '执行时间', 'DATE', 0, NULL, 3),
('NURSE_EXEC', 'nurse', '执行护士', 'STRING', 0, NULL, 4),
('NURSE_EXEC', 'exec_status', '执行状态', 'ENUM', 0, '已执行/未执行', 5);

INSERT INTO bm_relation (from_concept, to_concept, relation_name, description) VALUES
('MEDICAL_ORDER', 'NURSE_EXEC', '执行确认', '护士站确认医嘱执行（动作ACT-ORDER-EXEC的落点）');

INSERT INTO bm_term (term, concept_code, source_product, term_type, code_system) VALUES
('执行确认', 'NURSE_EXEC', '平台标准', 'STANDARD', '平台标准'),
('执行单', 'NURSE_EXEC', '护士站', 'ALIAS', '平台标准');

-- ---------- 数据源注册与映射 ----------
INSERT INTO bm_datasource (ds_code, ds_name, product_name, db_type, host, port, db_name, username, password) VALUES
('DS_NURSE', '护士站库', '护士站', 'MYSQL', '127.0.0.1', 3306, 'demo_nurse', '${demo_db_username}', '${demo_db_password}');

INSERT INTO bm_mapping (ds_code, table_name, column_name, concept_code, attr_code, value_map, confirmed, source) VALUES
('DS_NURSE', 'nurse_exec', 'exec_id', 'NURSE_EXEC', 'exec_id', NULL, 1, 'MANUAL'),
('DS_NURSE', 'nurse_exec', 'order_id', 'MEDICAL_ORDER', 'order_id', NULL, 1, 'MANUAL'),
('DS_NURSE', 'nurse_exec', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_NURSE', 'nurse_exec', 'exec_type', 'NURSE_EXEC', 'exec_type', NULL, 1, 'MANUAL'),
('DS_NURSE', 'nurse_exec', 'exec_time', 'NURSE_EXEC', 'exec_time', NULL, 1, 'MANUAL'),
('DS_NURSE', 'nurse_exec', 'nurse', 'NURSE_EXEC', 'nurse', NULL, 1, 'MANUAL'),
('DS_NURSE', 'nurse_exec', 'exec_status', 'NURSE_EXEC', 'exec_status', '{"1":"已执行","0":"未执行"}', 1, 'MANUAL');
