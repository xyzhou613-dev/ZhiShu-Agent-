-- =============================================================
-- V5: 医嘱全闭环演示 —— 新增药学产品线 + 缴费记录 + 本体扩展
-- 闭环链路：就诊(HIS) → 医嘱(HIS) → 计费(HIS) → 缴费(CHARGE)
--          → 检验申请/报告(LIS) / 调剂发药(PHARMACY) → 结算(CHARGE)
-- =============================================================

CREATE DATABASE IF NOT EXISTS demo_pharmacy DEFAULT CHARSET utf8mb4;

CREATE TABLE IF NOT EXISTS demo_pharmacy.dispense_record (
    dispense_id   VARCHAR(32) PRIMARY KEY COMMENT '发药流水号',
    order_id      VARCHAR(32) COMMENT 'HIS医嘱号',
    patient_no    VARCHAR(32) COMMENT '患者编号（住院号，口径差异同LIS）',
    item_code     VARCHAR(32),
    item_name     VARCHAR(128),
    status        VARCHAR(4) COMMENT '0已调剂 1已发药 2已退药',
    dispense_time DATETIME,
    pharmacist    VARCHAR(64) COMMENT '药师',
    KEY idx_order (order_id),
    KEY idx_patient (patient_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='药品调剂发药记录';

CREATE TABLE IF NOT EXISTS demo_charge.pay_record (
    pay_id    VARCHAR(32) PRIMARY KEY COMMENT '缴费流水号',
    inhos_no  VARCHAR(32) NOT NULL,
    amount    DECIMAL(10, 2),
    pay_type  VARCHAR(4) COMMENT '1预交金 2结算补缴 3退费',
    channel   VARCHAR(16) COMMENT '现金/扫码/医保',
    pay_time  DATETIME,
    operator  VARCHAR(64),
    KEY idx_inhos (inhos_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='缴费记录';

-- ---------- 本体扩展：药学域 + 两个新概念 ----------
INSERT INTO bm_domain (code, name, description, sort) VALUES
('PHARMACY', '药学域', '药品调剂、发药、退药', 6);

INSERT INTO bm_concept (code, name, domain_code, definition, owner, status, version) VALUES
('DISPENSE', '发药记录', 'PHARMACY', '药品医嘱在药房侧的调剂与发药执行记录。状态口径：已调剂/已发药/已退药。', '孙药学', 'PUBLISHED', 1),
('PAYMENT', '缴费记录', 'FEE', '患者在住院期间的缴费流水：入院预交金、出院结算补缴、退费。', '陈财务', 'PUBLISHED', 1);

INSERT INTO bm_attribute (concept_code, attr_code, attr_name, data_type, is_key, definition, sort) VALUES
('DISPENSE', 'dispense_id', '发药流水号', 'STRING', 1, NULL, 1),
('DISPENSE', 'item_code', '药品编码', 'STRING', 0, NULL, 2),
('DISPENSE', 'item_name', '药品名称', 'STRING', 0, NULL, 3),
('DISPENSE', 'status', '发药状态', 'ENUM', 0, '标准口径：已调剂/已发药/已退药', 4),
('DISPENSE', 'dispense_time', '发药时间', 'DATE', 0, NULL, 5),
('DISPENSE', 'pharmacist', '药师', 'STRING', 0, NULL, 6),
('PAYMENT', 'pay_id', '缴费流水号', 'STRING', 1, NULL, 1),
('PAYMENT', 'amount', '缴费金额', 'NUMBER', 0, '单位：元', 2),
('PAYMENT', 'pay_type', '缴费类型', 'ENUM', 0, '预交金/结算补缴/退费', 3),
('PAYMENT', 'channel', '支付渠道', 'ENUM', 0, '现金/扫码/医保', 4),
('PAYMENT', 'pay_time', '缴费时间', 'DATE', 0, NULL, 5);

INSERT INTO bm_relation (from_concept, to_concept, relation_name, description) VALUES
('MEDICAL_ORDER', 'DISPENSE', '调剂发药', '药品医嘱在药房生成调剂发药记录'),
('INP_VISIT', 'PAYMENT', '缴纳', '就诊过程产生预交金与结算缴费');

INSERT INTO bm_term (term, concept_code, source_product, term_type) VALUES
('发药记录', 'DISPENSE', '平台标准', 'STANDARD'),
('发药单', 'DISPENSE', '药房系统', 'ALIAS'),
('处方', 'MEDICAL_ORDER', '门诊/药房', 'ALIAS'),
('缴费记录', 'PAYMENT', '平台标准', 'STANDARD'),
('押金', 'PAYMENT', '收费系统', 'ALIAS');

-- ---------- 数据源注册：药房系统 ----------
INSERT INTO bm_datasource (ds_code, ds_name, product_name, db_type, host, port, db_name, username, password) VALUES
('DS_PHARMACY', '药房发药库', '药房系统', 'MYSQL', '127.0.0.1', 3306, 'demo_pharmacy', '${demo_db_username}', '${demo_db_password}');

-- ---------- 新表映射 ----------
INSERT INTO bm_mapping (ds_code, table_name, column_name, concept_code, attr_code, value_map, confirmed, source) VALUES
('DS_PHARMACY', 'dispense_record', 'dispense_id', 'DISPENSE', 'dispense_id', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'dispense_record', 'order_id', 'MEDICAL_ORDER', 'order_id', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'dispense_record', 'patient_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'dispense_record', 'item_code', 'DISPENSE', 'item_code', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'dispense_record', 'item_name', 'DISPENSE', 'item_name', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'dispense_record', 'status', 'DISPENSE', 'status', '{"0":"已调剂","1":"已发药","2":"已退药"}', 1, 'MANUAL'),
('DS_PHARMACY', 'dispense_record', 'dispense_time', 'DISPENSE', 'dispense_time', NULL, 1, 'MANUAL'),
('DS_PHARMACY', 'dispense_record', 'pharmacist', 'DISPENSE', 'pharmacist', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'pay_record', 'pay_id', 'PAYMENT', 'pay_id', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'pay_record', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'pay_record', 'amount', 'PAYMENT', 'amount', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'pay_record', 'pay_type', 'PAYMENT', 'pay_type', '{"1":"预交金","2":"结算补缴","3":"退费"}', 1, 'MANUAL'),
('DS_CHARGE', 'pay_record', 'channel', 'PAYMENT', 'channel', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'pay_record', 'pay_time', 'PAYMENT', 'pay_time', NULL, 1, 'MANUAL');
