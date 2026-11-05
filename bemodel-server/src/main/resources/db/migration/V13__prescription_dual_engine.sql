-- =============================================================
-- V13: 处方审核双引擎互证 —— 平台规则引擎补齐过敏/剂量类型（与 SHACL 同源）
-- =============================================================

-- ---------- 1) 药品相互作用（drug_dict 补相互作用列；数据由 DataSeeder 播种） ----------
ALTER TABLE demo_pharmacy.drug_dict
    ADD COLUMN interacts_with VARCHAR(128) NULL COMMENT '相互作用药品编码（逗号分隔）' AFTER child_forbidden;

-- ---------- 2) 新公理（处方审核三件套） ----------
INSERT INTO bm_axiom (axiom_code, subject, predicate, object, axiom_type, description) VALUES
('AX-007', '药品', '互斥', '患者过敏原', '互斥', '患者过敏的药品不得开立（过敏禁忌）'),
('AX-008', '药品医嘱', '约束', '日最大剂量', '约束', '单日剂量不得超过药品说明书日最大剂量'),
('AX-009', '药品', '互斥', '相互作用药品', '互斥', '存在相互作用的药品不得在同一就诊中联用');

-- ---------- 3) 平台质控新规则（纯配置、发布即生效，与 SHACL Shape 1/2 同一语义） ----------
INSERT INTO bm_rule (rule_code, name, concept_code, rule_type, expression, metric_code, severity, owner, status, version, engine, expr_json) VALUES
('RULE-QC-007', '过敏禁忌审核', 'PRESC_REVIEW', '校验',
 '患者过敏的药品不得开立：过敏史（EMR）与药品过敏原（药房字典）经本体映射跨库比对。',
 NULL, '高', '质控科 何静', 'PUBLISHED', 1, 'QC',
 '{"type":"ALLERGY_DISJOINT","axiom":"AX-007"}'),
('RULE-QC-008', '剂量上限审核', 'PRESC_REVIEW', '校验',
 '单日剂量（单次剂量×频次）不得超过药品日最大剂量。',
 NULL, '高', '质控科 何静', 'PUBLISHED', 1, 'QC',
 '{"type":"DOSE_LIMIT","axiom":"AX-008"}');
