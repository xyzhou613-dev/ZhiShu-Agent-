-- =============================================================
-- V16: 物资域进销存闭环 —— 申领→发放出库→扣减库存（套用药房进销存模式）
--      人事组织域下钻支撑：医嘱/执行/审核里的姓名对齐人员主数据
-- =============================================================

-- ---------- 1) 物资入库单 / 发放出库单（demo_material） ----------
CREATE TABLE IF NOT EXISTS demo_material.material_in (
    in_id VARCHAR(32) PRIMARY KEY COMMENT '入库单号',
    material_code VARCHAR(32) NOT NULL,
    material_name VARCHAR(128) NOT NULL,
    quantity INT NOT NULL,
    in_time DATETIME NOT NULL,
    operator VARCHAR(32) NOT NULL COMMENT '经办库管员',
    KEY idx_min_mat (material_code)
) COMMENT '耗材入库单（期初建账/采购补货）';

CREATE TABLE IF NOT EXISTS demo_material.material_out (
    out_id VARCHAR(32) PRIMARY KEY COMMENT '出库单号',
    apply_id VARCHAR(32) NOT NULL COMMENT '来源申领单号',
    material_code VARCHAR(32) NOT NULL,
    material_name VARCHAR(128) NOT NULL,
    quantity INT NOT NULL,
    out_dept VARCHAR(32) NOT NULL COMMENT '领用科室',
    out_time DATETIME NOT NULL,
    picker VARCHAR(32) NOT NULL COMMENT '发放库管员',
    KEY idx_mout_apply (apply_id),
    KEY idx_mout_mat (material_code)
) COMMENT '耗材发放出库单（申领发放即扣减库存）';

-- ---------- 2) 新概念 ----------
INSERT INTO bm_concept (code, name, domain_code, definition, owner, status, version) VALUES
('MATERIAL_IN', '耗材入库', 'MATERIAL', '耗材期初建账与采购补货的入库单据，入库即增加库存。', '吴物资', 'PUBLISHED', 1),
('MATERIAL_OUT', '耗材发放', 'MATERIAL', '库房按申领单向科室发放耗材的出库单据，发放出库即扣减库存。', '吴物资', 'PUBLISHED', 1);

INSERT INTO bm_attribute (concept_code, attr_code, attr_name, data_type, is_key, definition, sort) VALUES
('MATERIAL_IN', 'in_id', '入库单号', 'STRING', 1, NULL, 1),
('MATERIAL_IN', 'material_code', '耗材编码', 'STRING', 0, NULL, 2),
('MATERIAL_IN', 'quantity', '入库数量', 'NUMBER', 0, NULL, 3),
('MATERIAL_IN', 'in_time', '入库时间', 'DATE', 0, NULL, 4),
('MATERIAL_OUT', 'out_id', '出库单号', 'STRING', 1, NULL, 1),
('MATERIAL_OUT', 'apply_id', '来源申领单', 'STRING', 0, NULL, 2),
('MATERIAL_OUT', 'quantity', '发放数量', 'NUMBER', 0, NULL, 3),
('MATERIAL_OUT', 'out_dept', '领用科室', 'STRING', 0, NULL, 4),
('MATERIAL_OUT', 'out_time', '发放时间', 'DATE', 0, NULL, 5);

-- ---------- 3) 关系：申领→发放→扣减库存 两跳链（替换 V15 的直连关系） ----------
DELETE FROM bm_relation WHERE from_concept = 'MATERIAL_APPLY' AND to_concept = 'MATERIAL_STOCK';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description) VALUES
('MATERIAL_IN', 'MATERIAL_STOCK', '增加库存', '入库增加耗材库存'),
('MATERIAL_APPLY', 'MATERIAL_OUT', '发放出库', '申领单经库房发放生成出库单'),
('MATERIAL_OUT', 'MATERIAL_STOCK', '扣减库存', '发放出库扣减耗材库存');

INSERT INTO bm_term (term, concept_code, source_product, term_type, code_system) VALUES
('入库单', 'MATERIAL_IN', '物资系统', 'ALIAS', '平台标准'),
('出库单', 'MATERIAL_OUT', '物资系统', 'ALIAS', '平台标准'),
('发放单', 'MATERIAL_OUT', '库房口语', 'ALIAS', '平台标准');

-- ---------- 4) 映射 ----------
INSERT INTO bm_mapping (ds_code, table_name, column_name, concept_code, attr_code, value_map, confirmed, source) VALUES
('DS_MATERIAL', 'material_in', 'in_id', 'MATERIAL_IN', 'in_id', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_in', 'material_code', 'MATERIAL_IN', 'material_code', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_in', 'quantity', 'MATERIAL_IN', 'quantity', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_in', 'in_time', 'MATERIAL_IN', 'in_time', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_out', 'out_id', 'MATERIAL_OUT', 'out_id', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_out', 'apply_id', 'MATERIAL_OUT', 'apply_id', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_out', 'quantity', 'MATERIAL_OUT', 'quantity', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_out', 'out_dept', 'MATERIAL_OUT', 'out_dept', NULL, 1, 'MANUAL'),
('DS_MATERIAL', 'material_out', 'out_time', 'MATERIAL_OUT', 'out_time', NULL, 1, 'MANUAL');

-- ---------- 5) 治理规则：与药房 GOV-007 同模型的账实相符 + 发放溯源 ----------
INSERT INTO bm_gov_rule (rule_code, rule_name, rule_type, concept_code, severity, expr_json) VALUES
('GOV-011', '耗材库存账实相符', 'STOCK_BALANCE', 'MATERIAL_STOCK', '中',
 '{"type":"STOCK_BALANCE","ds":"DS_MATERIAL","stockTable":"material_stock","inTable":"material_in","outTable":"material_out","keyColumn":"material_code","qtyColumn":"quantity"}'),
('GOV-012', '发放必须关联申领', 'REF_INTACT', 'MATERIAL_OUT', '高',
 '{"type":"REF_INTACT","ds":"DS_MATERIAL","table":"material_out","column":"apply_id","refDs":"DS_MATERIAL","refTable":"material_apply","refColumn":"apply_id"}');
