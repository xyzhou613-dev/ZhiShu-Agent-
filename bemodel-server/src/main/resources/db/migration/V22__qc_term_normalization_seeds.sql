-- =============================================================
-- V22: 质控关键词术语归一（QcRuleEngine.termExpand）的同义词种子
--      同义组挂在独立小概念下——若挂 DIAGNOSIS 等大概念，
--      其名下高血压/肺炎等疾病名会互相"扩展"导致质控误报
-- =============================================================

INSERT INTO bm_concept (code, name, domain_code, definition, owner, status, version) VALUES
('PREGNANCY', '妊娠', 'CLINICAL', '妊娠状态及妊娠相关诊断。男性患者互斥（公理AX-003）。', '质控科 何静', 'PUBLISHED', 1),
('ALLERGEN_CEPHALOSPORIN', '头孢菌素类过敏', 'PHARMACY', '头孢菌素类药物过敏（过敏禁忌公理AX-007的过敏原词族）。', '质控科 何静', 'PUBLISHED', 1);

INSERT INTO bm_term (term, concept_code, source_product, term_type, code_system) VALUES
('妊娠', 'PREGNANCY', '平台标准', 'STANDARD', '平台标准'),
('怀孕', 'PREGNANCY', '临床口语', 'ALIAS', '平台标准'),
('Gestation', 'PREGNANCY', '英文术语', 'ALIAS', '平台标准'),
('头孢菌素', 'ALLERGEN_CEPHALOSPORIN', '平台标准', 'STANDARD', '平台标准'),
('头孢', 'ALLERGEN_CEPHALOSPORIN', '药房口语', 'ALIAS', '平台标准'),
('先锋霉素', 'ALLERGEN_CEPHALOSPORIN', '药品俗名', 'ALIAS', '平台标准');
