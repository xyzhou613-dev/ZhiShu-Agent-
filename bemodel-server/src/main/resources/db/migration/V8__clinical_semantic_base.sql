-- =============================================================
-- V8: 全院统一语义底座 —— 顶层医学本体 + SNOMED CT/ICD-10 对接 + 公理
--     + EMR 病案库（内涵质控数据源） + 质控结果表
-- =============================================================

-- ---------- 公理：实体关系的形式化约束（推理可追溯的依据） ----------
CREATE TABLE IF NOT EXISTS bm_axiom (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    axiom_code  VARCHAR(32) NOT NULL UNIQUE,
    subject     VARCHAR(128) NOT NULL COMMENT '主语（概念或限定表达式）',
    predicate   VARCHAR(32)  NOT NULL COMMENT '谓词动词',
    object      VARCHAR(128) NOT NULL COMMENT '宾语',
    axiom_type  VARCHAR(16)  NOT NULL COMMENT '依赖/互斥/支撑/因果/继承',
    description VARCHAR(512),
    status      VARCHAR(16) DEFAULT 'PUBLISHED',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='本体公理';

INSERT INTO bm_axiom (axiom_code, subject, predicate, object, axiom_type, description) VALUES
('AX-001', '诊断（并发症）', '依赖', '诊断（原发）', '依赖', '并发症不能独立存在，必须伴随原发诊断'),
('AX-002', '手术', '因果', '诊断', '因果', '手术必须有诊断指征，无指征手术属违规'),
('AX-003', '诊断（妊娠/妇科相关）', '互斥', '患者（男性）', '互斥', '男性患者不允许出现妊娠类/妇科类诊断'),
('AX-004', '检验报告', '支撑', '诊断', '支撑', '检验报告是诊断的客观依据，异常结果必须被诊断或处置覆盖'),
('AX-005', '医嘱', '存在性', '费用明细', '因果', '医嘱执行必然产生费用；取消必须联动退费'),
('AX-006', '并发症', '继承', '诊断', '继承', '并发症是诊断的子类型，继承诊断的全部属性');

-- ---------- 术语库扩展：外部标准编码体系对接 ----------
ALTER TABLE bm_term
    ADD COLUMN code_system   VARCHAR(32) COMMENT '编码体系：平台标准/SNOMED CT/ICD-10',
    ADD COLUMN standard_code VARCHAR(32) COMMENT '标准编码';

UPDATE bm_term SET code_system = '平台标准' WHERE code_system IS NULL;

-- SNOMED CT 中文对接示例（公开编码）+ ICD-10 对照，挂接「诊断」概念
INSERT INTO bm_term (term, concept_code, source_product, term_type, code_system, standard_code) VALUES
('高血压', 'DIAGNOSIS', 'SNOMED CT', 'STANDARD', 'SNOMED CT', '38341003'),
('2型糖尿病', 'DIAGNOSIS', 'SNOMED CT', 'STANDARD', 'SNOMED CT', '44054006'),
('肺炎', 'DIAGNOSIS', 'SNOMED CT', 'STANDARD', 'SNOMED CT', '233604007'),
('上呼吸道感染', 'DIAGNOSIS', 'SNOMED CT', 'STANDARD', 'SNOMED CT', '54150009'),
('急性胃肠炎', 'DIAGNOSIS', 'SNOMED CT', 'STANDARD', 'SNOMED CT', '40956001'),
('高血压', 'DIAGNOSIS', 'ICD-10', 'STANDARD', 'ICD-10', 'I10'),
('2型糖尿病', 'DIAGNOSIS', 'ICD-10', 'STANDARD', 'ICD-10', 'E11'),
('肺部感染', 'DIAGNOSIS', 'ICD-10', 'STANDARD', 'ICD-10', 'J98.4'),
('骨折', 'DIAGNOSIS', 'ICD-10', 'STANDARD', 'ICD-10', 'S82');

-- ---------- 临床核心概念 ----------
INSERT INTO bm_concept (code, name, domain_code, definition, owner, status, version) VALUES
('DIAGNOSIS', '诊断', 'CLINICAL', '对患者疾病或健康状态的医学判断。分主要诊断/其他诊断/并发症。对接SNOMED CT与ICD-10。', '李临床', 'PUBLISHED', 1),
('PROCEDURE', '手术', 'CLINICAL', '有创诊疗操作，必须有诊断指征（公理AX-002），术前须完成必查检验。', '李临床', 'PUBLISHED', 1),
('EMR_RECORD', '病案', 'CLINICAL', '患者诊疗过程的结构化文书：入院记录/出院小结/病案首页。内涵质控对象。', '李临床', 'PUBLISHED', 1);

INSERT INTO bm_attribute (concept_code, attr_code, attr_name, data_type, is_key, definition, sort) VALUES
('DIAGNOSIS', 'diag_code', '诊断编码', 'STRING', 1, NULL, 1),
('DIAGNOSIS', 'diag_name', '诊断名称', 'STRING', 0, NULL, 2),
('DIAGNOSIS', 'diag_type', '诊断类型', 'ENUM', 0, '主要诊断/其他诊断/并发症', 3),
('DIAGNOSIS', 'snomed_code', 'SNOMED CT编码', 'STRING', 0, '国际术语标准编码', 4),
('PROCEDURE', 'proc_code', '手术编码', 'STRING', 1, NULL, 1),
('PROCEDURE', 'proc_name', '手术名称', 'STRING', 0, NULL, 2),
('PROCEDURE', 'proc_time', '手术时间', 'DATE', 0, NULL, 3),
('PROCEDURE', 'surgeon', '术者', 'STRING', 0, NULL, 4),
('EMR_RECORD', 'record_id', '病案号', 'STRING', 1, NULL, 1),
('EMR_RECORD', 'record_type', '病案类型', 'ENUM', 0, '入院记录/出院小结/病案首页', 2),
('EMR_RECORD', 'diag_main', '主要诊断', 'STRING', 0, NULL, 3),
('EMR_RECORD', 'doctor', '书写医生', 'STRING', 0, NULL, 4),
('EMR_RECORD', 'create_time', '书写时间', 'DATE', 0, NULL, 5);

INSERT INTO bm_relation (from_concept, to_concept, relation_name, description) VALUES
('INP_VISIT', 'DIAGNOSIS', '确诊', '就诊过程中确立诊断'),
('DIAGNOSIS', 'PROCEDURE', '指导', '诊断是手术的指征依据'),
('LAB_REPORT', 'DIAGNOSIS', '支撑', '检验报告支撑诊断（公理AX-004）'),
('INP_VISIT', 'EMR_RECORD', '形成', '诊疗过程形成病案文书');

-- ---------- 质控规则种子（内涵质控：逻辑矛盾，不止格式） ----------
INSERT INTO bm_rule (rule_code, name, concept_code, rule_type, expression, metric_code, severity, owner, status, version) VALUES
('RULE-QC-001', '诊断依据完整性', 'DIAGNOSIS', '校验', '诊断须有客观依据支撑：糖尿病→空腹血糖检验或降糖药；感染类→抗生素用药。', NULL, '高', '质控科 何静', 'PUBLISHED', 1),
('RULE-QC-002', '术前必查完整性', 'PROCEDURE', '约束', '手术前必须完成血常规与凝血四项，且报告时间早于手术时间。', NULL, '高', '质控科 何静', 'PUBLISHED', 1),
('RULE-QC-003', '检验异常须处置', 'LAB_REPORT', '约束', '检验结果异常的报告，患者须有对应诊断或处置医嘱（公理AX-004）。', NULL, '高', '质控科 何静', 'PUBLISHED', 1),
('RULE-QC-004', '并发症须有处置', 'DIAGNOSIS', '约束', '诊断列表含并发症时，须有对应该并发症的用药或手术处置（公理AX-001/AX-006）。', NULL, '中', '质控科 何静', 'PUBLISHED', 1),
('RULE-QC-005', '性别诊断互斥', 'DIAGNOSIS', '校验', '男性患者不得出现妊娠类/妇科类诊断（公理AX-003）。', NULL, '高', '质控科 何静', 'PUBLISHED', 1);

-- ---------- demo_emr：电子病历产品线 ----------
CREATE DATABASE IF NOT EXISTS demo_emr DEFAULT CHARSET utf8mb4;

CREATE TABLE IF NOT EXISTS demo_emr.emr_record (
    record_id    VARCHAR(32) PRIMARY KEY,
    inhos_no     VARCHAR(32) NOT NULL,
    patient_name VARCHAR(64),
    record_type  VARCHAR(16) COMMENT '入院记录/出院小结/病案首页',
    diag_main    VARCHAR(128) COMMENT '主要诊断',
    diag_list    VARCHAR(512) COMMENT '全部诊断，逗号分隔（含并发症）',
    content      VARCHAR(1024) COMMENT '病历摘要',
    doctor       VARCHAR(64),
    create_time  DATETIME,
    qc_status    VARCHAR(8) DEFAULT '未质控' COMMENT '未质控/通过/不通过',
    KEY idx_inhos (inhos_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='病案记录';

CREATE TABLE IF NOT EXISTS demo_emr.emr_surgery (
    surg_id   VARCHAR(32) PRIMARY KEY,
    inhos_no  VARCHAR(32) NOT NULL,
    proc_name VARCHAR(128),
    proc_time DATETIME,
    surgeon   VARCHAR(64),
    proc_level VARCHAR(8) COMMENT '手术级别 一/二/三级',
    KEY idx_inhos (inhos_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='手术记录';

CREATE TABLE IF NOT EXISTS demo_emr.diag_dict (
    diag_code   VARCHAR(16) PRIMARY KEY,
    diag_name   VARCHAR(128),
    snomed_code VARCHAR(16) COMMENT 'SNOMED CT 编码',
    icd10       VARCHAR(16) COMMENT 'ICD-10 编码'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='诊断字典（对接标准编码）';

-- ---------- 数据源注册与映射 ----------
INSERT INTO bm_datasource (ds_code, ds_name, product_name, db_type, host, port, db_name, username, password) VALUES
('DS_EMR', '电子病历库', 'EMR', 'MYSQL', '127.0.0.1', 3306, 'demo_emr', '${demo_db_username}', '${demo_db_password}');

INSERT INTO bm_mapping (ds_code, table_name, column_name, concept_code, attr_code, value_map, confirmed, source) VALUES
('DS_EMR', 'emr_record', 'record_id', 'EMR_RECORD', 'record_id', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_record', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_record', 'patient_name', 'PATIENT', 'name', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_record', 'record_type', 'EMR_RECORD', 'record_type', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_record', 'diag_main', 'DIAGNOSIS', 'diag_name', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_record', 'doctor', 'EMR_RECORD', 'doctor', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_record', 'create_time', 'EMR_RECORD', 'create_time', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_surgery', 'surg_id', 'PROCEDURE', 'proc_code', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_surgery', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_surgery', 'proc_name', 'PROCEDURE', 'proc_name', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_surgery', 'proc_time', 'PROCEDURE', 'proc_time', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_surgery', 'surgeon', 'PROCEDURE', 'surgeon', NULL, 1, 'MANUAL'),
('DS_EMR', 'diag_dict', 'diag_code', 'DIAGNOSIS', 'diag_code', NULL, 1, 'MANUAL'),
('DS_EMR', 'diag_dict', 'diag_name', 'DIAGNOSIS', 'diag_name', NULL, 1, 'MANUAL'),
('DS_EMR', 'diag_dict', 'snomed_code', 'DIAGNOSIS', 'snomed_code', NULL, 1, 'MANUAL');

-- ---------- 质控结果表（含溯源链） ----------
CREATE TABLE IF NOT EXISTS qc_result (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    record_id   VARCHAR(32) NOT NULL,
    inhos_no    VARCHAR(32),
    record_type VARCHAR(16),
    pass_flag   TINYINT COMMENT '1通过 0不通过',
    findings_json TEXT COMMENT '质控发现（含规则/公理/证据）',
    trace_json  TEXT COMMENT '溯源链：本体版本+引用规则+引用公理+涉及概念',
    llm_used    TINYINT DEFAULT 0,
    llm_summary TEXT COMMENT 'LLM质控意见',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_record (record_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='病案内涵质控结果';
