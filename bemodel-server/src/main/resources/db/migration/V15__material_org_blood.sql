-- =============================================================
-- V15: 业务域再扩充 —— 物资耗材域/人事组织域 + 输血/院感/随访落表
-- =============================================================

-- ---------- 1) 新业务域 ----------
INSERT INTO bm_domain (code, name, description, sort) VALUES
('MATERIAL', '物资耗材域', '卫生材料、低值易耗品的库存与申领管理', 15),
('ORG', '人事组织域', '科室与医务人员主数据，医嘱/执行的责任主体', 16);

-- ---------- 2) 新产品库（物资系统）与物理表 ----------
CREATE DATABASE IF NOT EXISTS demo_material DEFAULT CHARSET utf8mb4;

CREATE TABLE IF NOT EXISTS demo_material.material_stock (
    material_code VARCHAR(32) PRIMARY KEY COMMENT '耗材编码',
    material_name VARCHAR(128) NOT NULL,
    spec VARCHAR(64) COMMENT '规格型号',
    quantity INT NOT NULL COMMENT '当前库存',
    unit VARCHAR(16) NOT NULL,
    warehouse VARCHAR(32) NOT NULL DEFAULT '中心库房',
    updated_at DATETIME NOT NULL
) COMMENT '物资耗材库存（卫材）';

CREATE TABLE IF NOT EXISTS demo_material.material_apply (
    apply_id VARCHAR(32) PRIMARY KEY COMMENT '申领单号',
    material_code VARCHAR(32) NOT NULL,
    material_name VARCHAR(128) NOT NULL,
    quantity INT NOT NULL,
    apply_dept VARCHAR(32) NOT NULL COMMENT '申领科室',
    apply_time DATETIME NOT NULL,
    status VARCHAR(8) NOT NULL COMMENT '待发/已发',
    picker VARCHAR(32) NULL COMMENT '库管员'
) COMMENT '科室耗材申领单';

CREATE TABLE IF NOT EXISTS demo_his.dept (
    dept_code VARCHAR(32) PRIMARY KEY,
    dept_name VARCHAR(64) NOT NULL,
    ward VARCHAR(64) COMMENT '所属病区',
    category VARCHAR(16) NOT NULL COMMENT '临床/医技/职能',
    bed_count INT NOT NULL DEFAULT 0
) COMMENT '科室主数据';

CREATE TABLE IF NOT EXISTS demo_his.staff (
    staff_id VARCHAR(32) PRIMARY KEY,
    staff_name VARCHAR(32) NOT NULL,
    role VARCHAR(16) NOT NULL COMMENT '医生/护士/药师/技师/麻醉师',
    title VARCHAR(32) COMMENT '职称',
    dept_code VARCHAR(32) NOT NULL,
    KEY idx_staff_dept (dept_code)
) COMMENT '医务人员主数据';

CREATE TABLE IF NOT EXISTS demo_his.blood_apply (
    apply_id VARCHAR(32) PRIMARY KEY,
    inhos_no VARCHAR(32) NOT NULL,
    patient_name VARCHAR(32) NOT NULL,
    blood_type VARCHAR(8) NOT NULL COMMENT '血型',
    component VARCHAR(32) NOT NULL COMMENT '血液成分（全血/红细胞/血浆/血小板）',
    quantity DECIMAL(6,1) NOT NULL COMMENT '申请量（单位）',
    apply_time DATETIME NOT NULL,
    status VARCHAR(8) NOT NULL COMMENT '已申请/已配血/已发血'
) COMMENT '输血申请';

CREATE TABLE IF NOT EXISTS demo_his.blood_transfusion (
    trans_id VARCHAR(32) PRIMARY KEY,
    apply_id VARCHAR(32) NOT NULL,
    inhos_no VARCHAR(32) NOT NULL,
    blood_no VARCHAR(32) NOT NULL COMMENT '血袋号',
    trans_time DATETIME NOT NULL,
    nurse VARCHAR(32) NOT NULL,
    reaction VARCHAR(8) NOT NULL DEFAULT '无' COMMENT '输血反应 无/发热/过敏/溶血'
) COMMENT '输血记录';

CREATE TABLE IF NOT EXISTS demo_emr.infection_report (
    report_id VARCHAR(32) PRIMARY KEY,
    inhos_no VARCHAR(32) NOT NULL,
    patient_name VARCHAR(32) NOT NULL,
    infection_type VARCHAR(32) NOT NULL COMMENT '感染类型',
    pathogen VARCHAR(64) NULL COMMENT '病原体',
    report_time DATETIME NOT NULL,
    status VARCHAR(8) NOT NULL COMMENT '已上报/已核实/已结案'
) COMMENT '院感上报';

CREATE TABLE IF NOT EXISTS demo_emr.followup (
    fu_id VARCHAR(32) PRIMARY KEY,
    inhos_no VARCHAR(32) NOT NULL,
    patient_name VARCHAR(32) NOT NULL,
    fu_type VARCHAR(16) NOT NULL COMMENT '出院随访/慢病随访/满意度回访',
    plan_time DATETIME NOT NULL,
    done_time DATETIME NULL,
    status VARCHAR(8) NOT NULL COMMENT '待随访/已完成',
    content VARCHAR(256) NULL
) COMMENT '随访记录';

-- ---------- 3) 数据源注册 ----------
INSERT INTO bm_datasource (ds_code, ds_name, product_name, db_type, host, port, db_name, username, password) VALUES
('DS_MATERIAL', '物资耗材库', '物资系统', 'MYSQL', '127.0.0.1', 3306, 'demo_material', '${demo_db_username}', '${demo_db_password}');

-- ---------- 4) 新概念 ----------
INSERT INTO bm_concept (code, name, domain_code, definition, owner, status, version) VALUES
('MATERIAL_STOCK', '耗材库存', 'MATERIAL', '卫生材料在库房的实时存量，口径：库存=Σ入库-Σ出库（与药品库存同模型）。', '吴物资', 'PUBLISHED', 1),
('MATERIAL_APPLY', '耗材申领', 'MATERIAL', '临床科室向库房申领耗材的单据，发放后扣减库存。', '吴物资', 'PUBLISHED', 1),
('STAFF', '医务人员', 'ORG', '医生/护士/药师/技师等医务人员主数据，是医嘱开立、执行、审核的责任主体。', '王人事', 'PUBLISHED', 1),
('DEPT', '科室', 'ORG', '医院科室主数据（临床/医技/职能），人员与业务活动的组织单元。', '王人事', 'PUBLISHED', 1);

INSERT INTO bm_attribute (concept_code, attr_code, attr_name, data_type, is_key, definition, sort) VALUES
('MATERIAL_STOCK', 'material_code', '耗材编码', 'STRING', 1, NULL, 1),
('MATERIAL_STOCK', 'quantity', '库存数量', 'NUMBER', 0, NULL, 2),
('MATERIAL_STOCK', 'warehouse', '库房', 'STRING', 0, NULL, 3),
('MATERIAL_APPLY', 'apply_id', '申领单号', 'STRING', 1, NULL, 1),
('MATERIAL_APPLY', 'quantity', '申领数量', 'NUMBER', 0, NULL, 2),
('MATERIAL_APPLY', 'apply_dept', '申领科室', 'STRING', 0, NULL, 3),
('MATERIAL_APPLY', 'status', '申领状态', 'ENUM', 0, '待发/已发', 4),
('STAFF', 'staff_id', '工号', 'STRING', 1, NULL, 1),
('STAFF', 'staff_name', '姓名', 'STRING', 0, NULL, 2),
('STAFF', 'role', '角色', 'ENUM', 0, '医生/护士/药师/技师/麻醉师', 3),
('STAFF', 'title', '职称', 'STRING', 0, NULL, 4),
('DEPT', 'dept_code', '科室编码', 'STRING', 1, NULL, 1),
('DEPT', 'dept_name', '科室名称', 'STRING', 0, NULL, 2),
('DEPT', 'category', '科室类别', 'ENUM', 0, '临床/医技/职能', 3),
('DEPT', 'bed_count', '床位数', 'NUMBER', 0, NULL, 4);

INSERT INTO bm_relation (from_concept, to_concept, relation_name, description) VALUES
('MATERIAL_APPLY', 'MATERIAL_STOCK', '发放扣减库存', '申领单发放后扣减耗材库存'),
('STAFF', 'DEPT', '工作于', '医务人员隶属科室'),
('MEDICAL_ORDER', 'STAFF', '由谁开立', '医嘱的责任医生（主数据对齐）'),
('NURSE_EXEC', 'STAFF', '由谁执行', '执行确认的责任护士（主数据对齐）');

INSERT INTO bm_term (term, concept_code, source_product, term_type, code_system) VALUES
('卫材', 'MATERIAL_STOCK', '物资系统', 'ALIAS', '平台标准'),
('申领单', 'MATERIAL_APPLY', '科室口语', 'ALIAS', '平台标准'),
('职工', 'STAFF', '人事系统', 'ALIAS', '平台标准'),
('临床科室', 'DEPT', 'HIS', 'ALIAS', '平台标准');

-- ---------- 5) 映射 ----------
INSERT INTO bm_mapping (ds_code, table_name, column_name, concept_code, attr_code, value_map, confirmed, source) VALUES
('DS_MATERIAL', 'material_stock', 'material_code', 'MATERIAL_STOCK', 'material_code', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_stock', 'material_name', 'MATERIAL_STOCK', 'material_code', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_stock', 'quantity', 'MATERIAL_STOCK', 'quantity', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_stock', 'warehouse', 'MATERIAL_STOCK', 'warehouse', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_apply', 'apply_id', 'MATERIAL_APPLY', 'apply_id', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_apply', 'quantity', 'MATERIAL_APPLY', 'quantity', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_apply', 'apply_dept', 'MATERIAL_APPLY', 'apply_dept', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_apply', 'status', 'MATERIAL_APPLY', 'status', NULL, 1, 'MANUAL'),
('DS_HIS', 'staff', 'staff_id', 'STAFF', 'staff_id', NULL, 1, 'MANUAL'),
('DS_HIS', 'staff', 'staff_name', 'STAFF', 'staff_name', NULL, 1, 'MANUAL'),
('DS_HIS', 'staff', 'role', 'STAFF', 'role', NULL, 1, 'MANUAL'),
('DS_HIS', 'staff', 'title', 'STAFF', 'title', NULL, 1, 'MANUAL'),
('DS_HIS', 'dept', 'dept_code', 'DEPT', 'dept_code', NULL, 1, 'MANUAL'),
('DS_HIS', 'dept', 'dept_name', 'DEPT', 'dept_name', NULL, 1, 'MANUAL'),
('DS_HIS', 'dept', 'category', 'DEPT', 'category', NULL, 1, 'MANUAL'),
('DS_HIS', 'dept', 'bed_count', 'DEPT', 'bed_count', NULL, 1, 'MANUAL'),
('DS_HIS', 'blood_apply', 'apply_id', 'BLOOD_APPLY', 'apply_id', NULL, 1, 'MANUAL'),
('DS_HIS', 'blood_apply', 'blood_type', 'BLOOD_APPLY', 'blood_type', NULL, 1, 'MANUAL'),
('DS_HIS', 'blood_apply', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_HIS', 'blood_apply', 'patient_name', 'PATIENT', 'name', NULL, 1, 'MANUAL'),
('DS_HIS', 'blood_transfusion', 'trans_id', 'BLOOD_TRANSFUSION', 'trans_id', NULL, 1, 'MANUAL'),
('DS_HIS', 'blood_transfusion', 'reaction', 'BLOOD_TRANSFUSION', 'reaction', NULL, 1, 'MANUAL'),
('DS_HIS', 'blood_transfusion', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_EMR', 'infection_report', 'report_id', 'INFECTION_REPORT', 'report_id', NULL, 1, 'MANUAL'),
('DS_EMR', 'infection_report', 'infection_type', 'INFECTION_REPORT', 'infection_type', NULL, 1, 'MANUAL'),
('DS_EMR', 'infection_report', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_EMR', 'followup', 'fu_id', 'FOLLOWUP', 'fu_id', NULL, 1, 'MANUAL'),
('DS_EMR', 'followup', 'plan_time', 'FOLLOWUP', 'plan', NULL, 1, 'MANUAL'),
('DS_EMR', 'followup', 'status', 'FOLLOWUP', 'fu_id', NULL, 1, 'MANUAL');
