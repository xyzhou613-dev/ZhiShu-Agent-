-- =============================================================
-- V14: 业务域扩展 —— 面向全院复杂业务的本体骨架
-- 8 个新域（护理/手术麻醉/病案/医保/输血/院感/预约/随访），14 个新概念，3 张新物理表
-- =============================================================

-- ---------- 1) 新业务域 ----------
INSERT INTO bm_domain (code, name, description, sort) VALUES
('NURSING', '护理域', '护理计划、执行确认、生命体征监测、护理评估', 7),
('SURGERY', '手术麻醉域', '手术申请、手术、麻醉记录、术中护理', 8),
('MEDRECORD', '病案域', '病案首页、病案编码、病案质控（内涵质控）', 9),
('INSURANCE', '医保域', '医保支付、DRG/DIP 入组、医保审核', 10),
('BLOOD', '输血域', '输血申请、配血、发血、输血记录与反应', 11),
('INFECTION', '院感域', '感染上报、隔离管理、消毒监测', 12),
('APPOINTMENT', '预约域', '预约挂号、预约检查、号源管理', 13),
('FOLLOWUP', '随访域', '出院随访、慢病随访、满意度回访', 14);

-- ---------- 2) 既有概念归位（更贴切的域） ----------
UPDATE bm_concept SET domain_code='NURSING' WHERE code='NURSE_EXEC';
UPDATE bm_concept SET domain_code='SURGERY' WHERE code='PROCEDURE';
UPDATE bm_concept SET domain_code='MEDRECORD' WHERE code='EMR_RECORD';

-- ---------- 3) 新物理表 ----------
CREATE TABLE IF NOT EXISTS demo_nurse.vital_sign (
    vs_id VARCHAR(32) PRIMARY KEY COMMENT '体征记录号',
    inhos_no VARCHAR(32) NOT NULL,
    patient_name VARCHAR(32) NOT NULL,
    record_time DATETIME NOT NULL COMMENT '测量时间',
    temperature DECIMAL(4,1) COMMENT '体温(℃)',
    pulse INT COMMENT '脉搏(次/分)',
    bp VARCHAR(16) COMMENT '血压(mmHg)',
    nurse VARCHAR(32) NOT NULL COMMENT '测量护士',
    KEY idx_vs_inhos (inhos_no)
) COMMENT '生命体征记录（体温单）';

CREATE TABLE IF NOT EXISTS demo_emr.anesthesia_record (
    anes_id VARCHAR(32) PRIMARY KEY COMMENT '麻醉记录号',
    surg_id VARCHAR(32) NOT NULL COMMENT '关联手术',
    inhos_no VARCHAR(32) NOT NULL,
    anesthetist VARCHAR(32) NOT NULL COMMENT '麻醉医师',
    method VARCHAR(32) NOT NULL COMMENT '麻醉方式',
    start_time DATETIME NOT NULL,
    end_time DATETIME NULL
) COMMENT '麻醉记录（麻醉单）';

CREATE TABLE IF NOT EXISTS demo_emr.drg_group (
    drg_id VARCHAR(32) PRIMARY KEY,
    record_id VARCHAR(32) NOT NULL COMMENT '关联病案',
    inhos_no VARCHAR(32) NOT NULL,
    drg_code VARCHAR(16) NOT NULL COMMENT 'DRG编码',
    drg_name VARCHAR(64) NOT NULL,
    weight DECIMAL(6,3) NOT NULL COMMENT '权重',
    group_time DATETIME NOT NULL COMMENT '入组时间'
) COMMENT 'DRG 入组结果（医保支付口径）';

-- ---------- 4) 新概念 ----------
INSERT INTO bm_concept (code, name, domain_code, definition, owner, status, version) VALUES
('VITAL_SIGN', '生命体征', 'NURSING', '体温、脉搏、血压等护理常规测量记录，护士站体温单的结构化形态。', '赵护理', 'PUBLISHED', 1),
('NURSING_PLAN', '护理计划', 'NURSING', '按护理级别制定的护理方案（一级/二级/三级护理），驱动护理执行。', '赵护理', 'PUBLISHED', 1),
('SURG_APPLY', '手术申请', 'SURGERY', '手术排台前由临床科室提交的申请，含手术级别与审批状态。', '刘麻醉', 'PUBLISHED', 1),
('ANESTHESIA', '麻醉记录', 'SURGERY', '麻醉医师记录的麻醉方式、过程与用药，手术的必经配套文书（麻醉单）。', '刘麻醉', 'PUBLISHED', 1),
('MEDRECORD_HOME', '病案首页', 'MEDRECORD', '住院病案的首页摘要：主诊断、手术、费用汇总，是病案质控与 DRG 入组的输入。', '李病案', 'PUBLISHED', 1),
('MEDRECORD_CODING', '病案编码', 'MEDRECORD', '编码员对病案诊断/手术赋予 ICD-10/ICD-9 标准编码的过程与结果。', '李病案', 'PUBLISHED', 1),
('INS_PAYMENT', '医保支付', 'INSURANCE', '医保渠道的费用支付记录，与现金/扫码支付同口径管理。', '陈财务', 'PUBLISHED', 1),
('DRG_GROUP', 'DRG入组', 'INSURANCE', '病案按 DRG/DIP 规则归入的病组及权重，医保打包付费的核算单元。', '陈财务', 'PUBLISHED', 1),
('BLOOD_APPLY', '输血申请', 'BLOOD', '临床用血申请（血型、成分、申请量），须经输血科审核配血。', '钱输血', 'PUBLISHED', 1),
('BLOOD_TRANSFUSION', '输血记录', 'BLOOD', '血袋发血与输注执行记录，含输血反应监测。', '钱输血', 'PUBLISHED', 1),
('INFECTION_REPORT', '感染上报', 'INFECTION', '院内感染病例的上报登记（感染部位/病原体/状态），院感监管核心单据。', '孙院感', 'PUBLISHED', 1),
('ISOLATION_ORDER', '隔离医嘱', 'INFECTION', '对传染风险患者下达的隔离医嘱（接触/飞沫/空气隔离）。', '孙院感', 'PUBLISHED', 1),
('APPOINTMENT', '预约挂号', 'APPOINTMENT', '门诊就诊前的号源预约，就诊当日转化为挂号。', '王门诊', 'PUBLISHED', 1),
('FOLLOWUP', '随访', 'FOLLOWUP', '出院后随访计划与执行记录（慢病随访/满意度回访）。', '王门诊', 'PUBLISHED', 1);

-- ---------- 5) 概念属性 ----------
INSERT INTO bm_attribute (concept_code, attr_code, attr_name, data_type, is_key, definition, sort) VALUES
('VITAL_SIGN', 'vs_id', '体征记录号', 'STRING', 1, NULL, 1),
('VITAL_SIGN', 'record_time', '测量时间', 'DATE', 0, NULL, 2),
('VITAL_SIGN', 'temperature', '体温', 'NUMBER', 0, '℃', 3),
('VITAL_SIGN', 'pulse', '脉搏', 'NUMBER', 0, '次/分', 4),
('VITAL_SIGN', 'bp', '血压', 'STRING', 0, 'mmHg', 5),
('NURSING_PLAN', 'plan_id', '计划号', 'STRING', 1, NULL, 1),
('NURSING_PLAN', 'level', '护理级别', 'ENUM', 0, '特级/一级/二级/三级', 2),
('SURG_APPLY', 'apply_id', '申请号', 'STRING', 1, NULL, 1),
('SURG_APPLY', 'proc_name', '拟施手术', 'STRING', 0, NULL, 2),
('SURG_APPLY', 'proc_level', '手术级别', 'ENUM', 0, '一至四级', 3),
('ANESTHESIA', 'anes_id', '麻醉记录号', 'STRING', 1, NULL, 1),
('ANESTHESIA', 'method', '麻醉方式', 'ENUM', 0, '全麻/硬膜外/腰麻/局麻', 2),
('ANESTHESIA', 'anesthetist', '麻醉医师', 'STRING', 0, NULL, 3),
('MEDRECORD_HOME', 'record_id', '病案号', 'STRING', 1, NULL, 1),
('MEDRECORD_HOME', 'main_diag', '主要诊断', 'STRING', 0, NULL, 2),
('MEDRECORD_HOME', 'qc_status', '质控状态', 'ENUM', 0, '未质控/通过/不通过', 3),
('MEDRECORD_CODING', 'code_id', '编码号', 'STRING', 1, NULL, 1),
('MEDRECORD_CODING', 'icd10', 'ICD-10编码', 'STRING', 0, NULL, 2),
('INS_PAYMENT', 'pay_id', '支付流水号', 'STRING', 1, NULL, 1),
('INS_PAYMENT', 'channel', '支付渠道', 'ENUM', 0, '现金/扫码/医保', 2),
('INS_PAYMENT', 'amount', '支付金额', 'NUMBER', 0, NULL, 3),
('DRG_GROUP', 'drg_id', '入组号', 'STRING', 1, NULL, 1),
('DRG_GROUP', 'drg_code', 'DRG编码', 'STRING', 0, NULL, 2),
('DRG_GROUP', 'weight', '权重', 'NUMBER', 0, '医保支付权重', 3),
('BLOOD_APPLY', 'apply_id', '申请号', 'STRING', 1, NULL, 1),
('BLOOD_APPLY', 'blood_type', '血型', 'ENUM', 0, 'A/B/O/AB（Rh±）', 2),
('BLOOD_TRANSFUSION', 'trans_id', '输血号', 'STRING', 1, NULL, 1),
('BLOOD_TRANSFUSION', 'reaction', '输血反应', 'ENUM', 0, '无/发热/过敏/溶血', 2),
('INFECTION_REPORT', 'report_id', '上报号', 'STRING', 1, NULL, 1),
('INFECTION_REPORT', 'infection_type', '感染类型', 'ENUM', 0, '呼吸道/泌尿道/切口/血流', 2),
('ISOLATION_ORDER', 'iso_id', '隔离号', 'STRING', 1, NULL, 1),
('ISOLATION_ORDER', 'iso_type', '隔离类型', 'ENUM', 0, '接触/飞沫/空气', 2),
('APPOINTMENT', 'reg_id', '预约号', 'STRING', 1, NULL, 1),
('APPOINTMENT', 'reg_time', '预约时间', 'DATE', 0, NULL, 2),
('FOLLOWUP', 'fu_id', '随访号', 'STRING', 1, NULL, 1),
('FOLLOWUP', 'plan', '随访计划', 'STRING', 0, NULL, 2);

-- ---------- 6) 关系 ----------
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description) VALUES
('PATIENT', 'VITAL_SIGN', '日常监测', '患者住院期间的护理体征监测'),
('NURSING_PLAN', 'NURSE_EXEC', '驱动执行', '护理计划驱动护理执行确认'),
('SURG_APPLY', 'PROCEDURE', '经审批实施', '手术申请审批通过后实施为手术'),
('PROCEDURE', 'ANESTHESIA', '需麻醉配套', '手术须有麻醉记录（全麻/椎管内等）'),
('EMR_RECORD', 'DRG_GROUP', '入组', '病案按 DRG 规则入组'),
('SETTLEMENT', 'INS_PAYMENT', '含医保支付', '结算中含医保渠道支付'),
('APPOINTMENT', 'REGISTER', '转化为挂号', '预约到院后转化为正式挂号'),
('BLOOD_APPLY', 'BLOOD_TRANSFUSION', '配血后输注', '输血申请经配血后执行输注'),
('DIAGNOSIS', 'INFECTION_REPORT', '可能触发', '感染相关诊断触发院感上报');

-- ---------- 7) 术语 ----------
INSERT INTO bm_term (term, concept_code, source_product, term_type, code_system) VALUES
('体温单', 'VITAL_SIGN', '护士站', 'ALIAS', '平台标准'),
('麻醉单', 'ANESTHESIA', '麻醉系统', 'ALIAS', '平台标准'),
('首页', 'MEDRECORD_HOME', '病案系统', 'ALIAS', '平台标准'),
('医保结算', 'INS_PAYMENT', '医保接口', 'ALIAS', '平台标准'),
('DIP分组', 'DRG_GROUP', '医保口径', 'ALIAS', '平台标准'),
('号源', 'APPOINTMENT', '预约系统', 'ALIAS', '平台标准'),
('配血单', 'BLOOD_APPLY', '输血科', 'ALIAS', '平台标准'),
('随访电话', 'FOLLOWUP', '客服口语', 'ALIAS', '平台标准');

-- ---------- 8) 映射（3 张新表 + 3 张既有表的新概念视角） ----------
INSERT INTO bm_mapping (ds_code, table_name, column_name, concept_code, attr_code, value_map, confirmed, source) VALUES
('DS_NURSE', 'vital_sign', 'vs_id', 'VITAL_SIGN', 'vs_id', NULL, 1, 'MANUAL'),
('DS_NURSE', 'vital_sign', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_NURSE', 'vital_sign', 'patient_name', 'PATIENT', 'name', NULL, 1, 'MANUAL'),
('DS_NURSE', 'vital_sign', 'record_time', 'VITAL_SIGN', 'record_time', NULL, 1, 'MANUAL'),
('DS_NURSE', 'vital_sign', 'temperature', 'VITAL_SIGN', 'temperature', NULL, 1, 'MANUAL'),
('DS_NURSE', 'vital_sign', 'pulse', 'VITAL_SIGN', 'pulse', NULL, 1, 'MANUAL'),
('DS_NURSE', 'vital_sign', 'bp', 'VITAL_SIGN', 'bp', NULL, 1, 'MANUAL'),
('DS_EMR', 'anesthesia_record', 'anes_id', 'ANESTHESIA', 'anes_id', NULL, 1, 'MANUAL'),
('DS_EMR', 'anesthesia_record', 'surg_id', 'PROCEDURE', 'proc_id', NULL, 1, 'MANUAL'),
('DS_EMR', 'anesthesia_record', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_EMR', 'anesthesia_record', 'anesthetist', 'ANESTHESIA', 'anesthetist', NULL, 1, 'MANUAL'),
('DS_EMR', 'anesthesia_record', 'method', 'ANESTHESIA', 'method', NULL, 1, 'MANUAL'),
('DS_EMR', 'drg_group', 'drg_id', 'DRG_GROUP', 'drg_id', NULL, 1, 'MANUAL'),
('DS_EMR', 'drg_group', 'record_id', 'EMR_RECORD', 'record_id', NULL, 1, 'MANUAL'),
('DS_EMR', 'drg_group', 'drg_code', 'DRG_GROUP', 'drg_code', NULL, 1, 'MANUAL'),
('DS_EMR', 'drg_group', 'drg_name', 'DRG_GROUP', 'drg_code', NULL, 1, 'MANUAL'),
('DS_EMR', 'drg_group', 'weight', 'DRG_GROUP', 'weight', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_reg', 'reg_id', 'APPOINTMENT', 'reg_id', NULL, 1, 'MANUAL'),
('DS_OPD', 'opd_reg', 'reg_time', 'APPOINTMENT', 'reg_time', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'pay_record', 'pay_id', 'INS_PAYMENT', 'pay_id', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'pay_record', 'amount', 'INS_PAYMENT', 'amount', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'pay_record', 'channel', 'INS_PAYMENT', 'channel', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_record', 'record_id', 'MEDRECORD_HOME', 'record_id', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_record', 'diag_main', 'MEDRECORD_HOME', 'main_diag', NULL, 1, 'MANUAL'),
('DS_EMR', 'emr_record', 'qc_status', 'MEDRECORD_HOME', 'qc_status', NULL, 1, 'MANUAL');
