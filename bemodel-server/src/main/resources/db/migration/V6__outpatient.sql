-- =============================================================
-- V6: 门诊业务线平移 —— 证明本体框架可复制：
-- 门诊「处方」复用 MEDICAL_ORDER 概念，挂号/门诊就诊/就诊卡号靠映射统一口径
-- 门诊执行仍落在共享执行系统（LIS/药房/收费），符合真实医院架构
-- =============================================================

CREATE DATABASE IF NOT EXISTS demo_opd DEFAULT CHARSET utf8mb4;

CREATE TABLE IF NOT EXISTS demo_opd.opd_reg (
    reg_id       VARCHAR(32) PRIMARY KEY COMMENT '挂号流水号',
    pat_card_no  VARCHAR(32) NOT NULL COMMENT '就诊卡号（门诊患者主键，口径差异）',
    pat_name     VARCHAR(64),
    reg_dept     VARCHAR(64),
    reg_doctor   VARCHAR(64),
    reg_fee      DECIMAL(10, 2) COMMENT '挂号费',
    reg_status   VARCHAR(4) COMMENT '1已挂号 2已退号',
    reg_time     DATETIME,
    KEY idx_card (pat_card_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='门诊挂号';

CREATE TABLE IF NOT EXISTS demo_opd.opd_visit (
    visit_id   VARCHAR(32) PRIMARY KEY,
    card_no    VARCHAR(32) NOT NULL,
    diag       VARCHAR(256) COMMENT '门诊诊断',
    visit_time DATETIME,
    doctor     VARCHAR(64),
    status     VARCHAR(4) COMMENT '1接诊中 2已完成',
    KEY idx_card (card_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='门诊看诊记录';

CREATE TABLE IF NOT EXISTS demo_opd.opd_presc (
    presc_id     VARCHAR(32) PRIMARY KEY COMMENT '处方流水号',
    visit_id     VARCHAR(32),
    card_no      VARCHAR(32),
    item_code    VARCHAR(32),
    item_name    VARCHAR(128),
    item_type    VARCHAR(16) COMMENT '药品/检验/检查',
    quantity     INT COMMENT '数量',
    price        DECIMAL(10, 2) COMMENT '单项金额',
    presc_status VARCHAR(4) COMMENT '0已作废 1已开立(未缴费) 2已缴费 3已执行',
    create_time  DATETIME,
    KEY idx_visit (visit_id),
    KEY idx_card (card_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='门诊处方（含检验检查申请）';

-- ---------- 本体扩展：门诊就诊 + 挂号 ----------
INSERT INTO bm_concept (code, name, domain_code, definition, owner, status, version) VALUES
('OPD_VISIT', '门诊就诊', 'PATIENT_DOMAIN', '患者一次门诊过程，以就诊卡号为标识，从挂号到看诊完成。门诊与住院患者主索引不同（卡号 vs 住院号），靠映射统一。', '王架构', 'PUBLISHED', 1),
('REGISTER', '挂号记录', 'PATIENT_DOMAIN', '门诊就诊的前置登记，含科室、医生、挂号费。', '王架构', 'PUBLISHED', 1);

INSERT INTO bm_attribute (concept_code, attr_code, attr_name, data_type, is_key, definition, sort) VALUES
('OPD_VISIT', 'visit_id', '门诊就诊号', 'STRING', 1, NULL, 1),
('OPD_VISIT', 'card_no', '就诊卡号', 'STRING', 0, '门诊患者标识', 2),
('OPD_VISIT', 'diag', '门诊诊断', 'STRING', 0, NULL, 3),
('OPD_VISIT', 'visit_time', '看诊时间', 'DATE', 0, NULL, 4),
('OPD_VISIT', 'doctor', '看诊医生', 'STRING', 0, NULL, 5),
('OPD_VISIT', 'status', '看诊状态', 'ENUM', 0, '接诊中/已完成', 6),
('REGISTER', 'reg_id', '挂号流水号', 'STRING', 1, NULL, 1),
('REGISTER', 'dept', '挂号科室', 'STRING', 0, NULL, 2),
('REGISTER', 'doctor', '挂号医生', 'STRING', 0, NULL, 3),
('REGISTER', 'reg_time', '挂号时间', 'DATE', 0, NULL, 4),
('REGISTER', 'status', '挂号状态', 'ENUM', 0, '已挂号/已退号', 5),
('REGISTER', 'fee', '挂号费', 'NUMBER', 0, NULL, 6);

INSERT INTO bm_relation (from_concept, to_concept, relation_name, description) VALUES
('PATIENT', 'OPD_VISIT', '门诊就诊', '患者门诊过程'),
('REGISTER', 'OPD_VISIT', '生成', '挂号后生成门诊就诊'),
('OPD_VISIT', 'MEDICAL_ORDER', '开立处方', '门诊处方复用医嘱概念（处方的标准口径即医嘱）');

INSERT INTO bm_term (term, concept_code, source_product, term_type) VALUES
('门诊就诊', 'OPD_VISIT', '平台标准', 'STANDARD'),
('看诊', 'OPD_VISIT', '门诊', 'ALIAS'),
('挂号', 'REGISTER', '平台标准', 'STANDARD'),
('就诊卡号', 'OPD_VISIT', '门诊', 'ALIAS');

-- ---------- 数据源注册：门诊系统 ----------
INSERT INTO bm_datasource (ds_code, ds_name, product_name, db_type, host, port, db_name, username, password) VALUES
('DS_OPD', '门诊系统库', '门诊HIS', 'MYSQL', '127.0.0.1', 3306, 'demo_opd', '${demo_db_username}', '${demo_db_password}');

-- ---------- 门诊库映射（处方状态 M:1 映射到医嘱标准状态，体现口径统一能力） ----------
INSERT INTO bm_mapping (ds_code, table_name, column_name, concept_code, attr_code, value_map, confirmed, source) VALUES
('DS_OPD', 'opd_reg', 'reg_id', 'REGISTER', 'reg_id', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_reg', 'pat_card_no', 'OPD_VISIT', 'card_no', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_reg', 'pat_name', 'PATIENT', 'name', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_reg', 'reg_dept', 'REGISTER', 'dept', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_reg', 'reg_doctor', 'REGISTER', 'doctor', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_reg', 'reg_fee', 'REGISTER', 'fee', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_reg', 'reg_status', 'REGISTER', 'status', '{"1":"已挂号","2":"已退号"}', 1, 'MANUAL'),
('DS_OPD', 'opd_reg', 'reg_time', 'REGISTER', 'reg_time', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_visit', 'visit_id', 'OPD_VISIT', 'visit_id', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_visit', 'card_no', 'OPD_VISIT', 'card_no', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_visit', 'diag', 'OPD_VISIT', 'diag', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_visit', 'visit_time', 'OPD_VISIT', 'visit_time', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_visit', 'doctor', 'OPD_VISIT', 'doctor', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_visit', 'status', 'OPD_VISIT', 'status', '{"1":"接诊中","2":"已完成"}', 1, 'MANUAL'),
('DS_OPD', 'opd_presc', 'presc_id', 'MEDICAL_ORDER', 'order_id', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_presc', 'visit_id', 'OPD_VISIT', 'visit_id', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_presc', 'card_no', 'OPD_VISIT', 'card_no', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_presc', 'item_code', 'MEDICAL_ORDER', 'item_code', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_presc', 'item_name', 'MEDICAL_ORDER', 'item_name', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_presc', 'presc_status', 'MEDICAL_ORDER', 'status', '{"0":"已取消","1":"未执行","2":"未执行","3":"已执行"}', 1, 'MANUAL'),
('DS_OPD', 'opd_presc', 'create_time', 'MEDICAL_ORDER', 'create_time', NULL, 1, 'MANUAL');

-- ---------- 缴费类型扩展：门诊缴费 ----------
UPDATE bm_mapping SET value_map = '{"1":"预交金","2":"结算补缴","3":"退费","4":"门诊缴费"}'
WHERE ds_code = 'DS_CHARGE' AND table_name = 'pay_record' AND column_name = 'pay_type';
UPDATE bm_attribute SET definition = '预交金/结算补缴/退费/门诊缴费'
WHERE concept_code = 'PAYMENT' AND attr_code = 'pay_type';
