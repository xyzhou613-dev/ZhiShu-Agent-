-- =============================================================
-- V24: 多父继承 —— 概念继承关系结构化（原仅落 bm_axiom 自由文本）
--      is_primary 只管展示主父；成环由服务层/OntologyCheck 拦截
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_concept_parent (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    child_code  VARCHAR(64) NOT NULL COMMENT '子概念',
    parent_code VARCHAR(64) NOT NULL COMMENT '父概念',
    is_primary  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '主父（展示用），同 child 唯一',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_child_parent (child_code, parent_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='概念多父继承（subClassOf 结构化）';

-- 伞概念：临床所见（对齐顶层模板 med:ClinicalFinding）
INSERT INTO bm_concept (code, name, domain_code, definition, owner, status, version) VALUES
('CLINICAL_FINDING', '临床所见', 'CLINICAL', '诊疗过程中产生的观察与判断结果：诊断、检验报告、检查报告、感染上报等。', '李临床', 'PUBLISHED', 1);

-- 真实继承种子（语义成立、与现有互斥无冲突；INFECTION_REPORT 双父演示多父继承）
INSERT INTO bm_concept_parent (child_code, parent_code, is_primary) VALUES
('DIAGNOSIS',        'CLINICAL_FINDING', 1),  -- 诊断是临床所见（模板：Diagnosis subClassOf ClinicalFinding）
('LAB_REPORT',       'CLINICAL_FINDING', 1),  -- 检验报告是临床所见（LabResult subClassOf ClinicalFinding）
('CHECK_REPORT',     'CLINICAL_FINDING', 1),  -- 检查报告是临床所见（ExamReport subClassOf ClinicalFinding）
('PREGNANCY',        'DIAGNOSIS',        1),  -- 妊娠（相关诊断）是诊断 → 三级链 PREGNANCY→DIAGNOSIS→CLINICAL_FINDING
('ISOLATION_ORDER',  'MEDICAL_ORDER',    1),  -- 隔离医嘱是医嘱（模板：LabOrder subClassOf Order）
('INFECTION_REPORT', 'CLINICAL_FINDING', 1),  -- 感染上报是临床所见（主父）
('INFECTION_REPORT', 'EMR_RECORD',       0);  -- 感染上报同时是病案文书（院感监管单据入病案，副父）
