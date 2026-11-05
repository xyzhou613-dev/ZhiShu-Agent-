-- =============================================================
-- V9: 规则引擎化（校验规则编译为可配置探针表达式，新规则免开发）
--     + PACS 检查报告产品线接入（检查医嘱闭环补齐最后一环）
-- =============================================================

-- ---------- 规则引擎：bm_rule 增加引擎类型与表达式 ----------
ALTER TABLE bm_rule
    ADD COLUMN engine    VARCHAR(16) COMMENT '执行引擎：QC=质控规则引擎',
    ADD COLUMN expr_json TEXT        COMMENT '规则表达式JSON（引擎解释执行，新规则免开发）';

-- 五条既有质控规则迁移为平台可配置表达式（语义与此前代码字典完全一致）
UPDATE bm_rule SET engine='QC', expr_json='{"type":"DIAG_REQUIRES_ITEM","cases":[{"diagKeywords":["糖尿病"],"kind":"LAB","codes":["L007"],"requireName":"空腹血糖检验"},{"diagKeywords":["高血压"],"kind":"DRUG","codes":["D009"],"requireName":"降压药（硝苯地平）"},{"diagKeywords":["感染","肺炎","脓毒"],"kind":"DRUG","codes":["D006","D011"],"requireName":"抗生素（头孢类）"}]}' WHERE rule_code='RULE-QC-001';
UPDATE bm_rule SET engine='QC', expr_json='{"type":"PREOP_REQUIRES","axiom":"AX-002","requires":[{"kind":"LAB","codes":["L001"],"requireName":"血常规"},{"kind":"LAB","codes":["L004"],"requireName":"凝血四项"}]}' WHERE rule_code='RULE-QC-002';
UPDATE bm_rule SET engine='QC', expr_json='{"type":"ABNORMAL_REQUIRES_COVER","axiom":"AX-004","coverDiagKeywords":["感染","肺炎","脓毒","炎"],"coverKind":"DRUG","coverCodes":["D006","D011"]}' WHERE rule_code='RULE-QC-003';
UPDATE bm_rule SET engine='QC', expr_json='{"type":"COMPLICATION_REQUIRES_ITEM","axiom":"AX-001","cases":[{"diagKeywords":["上消化道出血"],"kind":"DRUG","codes":["D007"],"requireName":"质子泵抑制剂（奥美拉唑）"},{"diagKeywords":["切口感染"],"kind":"DRUG","codes":["D006","D011"],"requireName":"抗生素（头孢类）"}]}' WHERE rule_code='RULE-QC-004';
UPDATE bm_rule SET engine='QC', expr_json='{"type":"SEX_DISJOINT_DIAG","axiom":"AX-003","sex":"男","diagKeywords":["妊娠","卵巢","子宫","宫颈"]}' WHERE rule_code='RULE-QC-005';

-- RULE-QC-006 检查报告异常须处置：PACS 接入后新增，全程零代码（仅插入一条规则数据即生效）
INSERT INTO bm_rule (rule_code, name, concept_code, rule_type, expression, metric_code, severity, owner, status, version, engine, expr_json) VALUES
('RULE-QC-006', '检查报告异常须处置', 'CHECK_REPORT', '约束',
 '检查（影像）报告结论异常时，患者须有对应诊断、进一步检查/手术等处置记录。',
 NULL, '高', '质控科 何静', 'PUBLISHED', 1, 'QC',
 '{"type":"EXAM_ABNORMAL_REQUIRES_COVER","axiom":"AX-004","coverDiagKeywords":["占位","结节","积液","肿瘤","破坏","感染"],"coverProcedure":true}');

-- ---------- PACS：影像检查产品线 ----------
CREATE DATABASE IF NOT EXISTS demo_pacs DEFAULT CHARSET utf8mb4;

CREATE TABLE IF NOT EXISTS demo_pacs.exam_report (
    exam_id      VARCHAR(32) PRIMARY KEY COMMENT '检查号',
    order_id     VARCHAR(32) COMMENT 'HIS医嘱号 / 门诊处方号',
    patient_no   VARCHAR(32) COMMENT '患者编号（住院号或就诊卡号）',
    patient_name VARCHAR(64),
    item_code    VARCHAR(32),
    item_name    VARCHAR(128),
    abnormal_flag VARCHAR(4) COMMENT 'Y异常 N正常',
    conclusion   VARCHAR(512) COMMENT '检查结论（影像所见）',
    exam_time    DATETIME COMMENT '检查时间',
    report_time  DATETIME COMMENT '报告时间',
    technician   VARCHAR(64) COMMENT '技师',
    reviewer     VARCHAR(64) COMMENT '审核医师',
    KEY idx_order (order_id),
    KEY idx_patient (patient_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='检查报告（PACS）';

-- ---------- 本体扩展：检查报告 ----------
INSERT INTO bm_concept (code, name, domain_code, definition, owner, status, version) VALUES
('CHECK_REPORT', '检查报告', 'MEDTECH', '影像/功能检查（CT/DR/B超等）的执行结果文书，PACS 产出。结论异常须有处置（RULE-QC-006）。', '张医技', 'PUBLISHED', 1);

INSERT INTO bm_attribute (concept_code, attr_code, attr_name, data_type, is_key, definition, sort) VALUES
('CHECK_REPORT', 'exam_id', '检查号', 'STRING', 1, NULL, 1),
('CHECK_REPORT', 'item_code', '项目编码', 'STRING', 0, NULL, 2),
('CHECK_REPORT', 'conclusion', '检查结论', 'STRING', 0, '影像所见文字', 3),
('CHECK_REPORT', 'abnormal', '是否异常', 'ENUM', 0, '正常/异常', 4),
('CHECK_REPORT', 'report_time', '报告时间', 'DATE', 0, NULL, 5),
('CHECK_REPORT', 'reviewer', '审核医师', 'STRING', 0, NULL, 6);

INSERT INTO bm_relation (from_concept, to_concept, relation_name, description) VALUES
('MEDICAL_ORDER', 'CHECK_REPORT', '生成检查报告', '检查类医嘱在PACS执行后产出报告');

INSERT INTO bm_term (term, concept_code, source_product, term_type, code_system) VALUES
('检查报告', 'CHECK_REPORT', '平台标准', 'STANDARD', '平台标准'),
('影像报告', 'CHECK_REPORT', 'PACS', 'ALIAS', '平台标准'),
('片子', 'CHECK_REPORT', '临床口语', 'ALIAS', '平台标准');

-- ---------- 数据源注册与映射 ----------
INSERT INTO bm_datasource (ds_code, ds_name, product_name, db_type, host, port, db_name, username, password) VALUES
('DS_PACS', '影像检查库', 'PACS', 'MYSQL', '127.0.0.1', 3306, 'demo_pacs', '${demo_db_username}', '${demo_db_password}');

INSERT INTO bm_mapping (ds_code, table_name, column_name, concept_code, attr_code, value_map, confirmed, source) VALUES
('DS_PACS', 'exam_report', 'exam_id', 'CHECK_REPORT', 'exam_id', NULL, 1, 'MANUAL'),
('DS_PACS', 'exam_report', 'order_id', 'MEDICAL_ORDER', 'order_id', NULL, 1, 'MANUAL'),
('DS_PACS', 'exam_report', 'patient_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_PACS', 'exam_report', 'patient_name', 'PATIENT', 'name', NULL, 1, 'MANUAL'),
('DS_PACS', 'exam_report', 'item_code', 'CHECK_REPORT', 'item_code', NULL, 1, 'MANUAL'),
('DS_PACS', 'exam_report', 'item_name', 'CHECK_REPORT', 'item_code', NULL, 1, 'MANUAL'),
('DS_PACS', 'exam_report', 'conclusion', 'CHECK_REPORT', 'conclusion', NULL, 1, 'MANUAL'),
('DS_PACS', 'exam_report', 'abnormal_flag', 'CHECK_REPORT', 'abnormal', '{"Y":"异常","N":"正常"}', 1, 'MANUAL'),
('DS_PACS', 'exam_report', 'report_time', 'CHECK_REPORT', 'report_time', NULL, 1, 'MANUAL'),
('DS_PACS', 'exam_report', 'reviewer', 'CHECK_REPORT', 'reviewer', NULL, 1, 'MANUAL');
