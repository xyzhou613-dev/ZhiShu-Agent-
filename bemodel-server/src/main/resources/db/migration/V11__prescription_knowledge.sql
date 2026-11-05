-- V11：处方审核原型数据底座（SHACL Shape 1/2 的 ABox 输入）
-- 1) HIS 医嘱补剂量/频次字段
-- 2) 药房药品知识字典（日最大剂量/过敏原/儿童禁用）
-- 3) EMR 患者过敏史

ALTER TABLE demo_his.medical_order
    ADD COLUMN single_dose DECIMAL(8,2) NULL COMMENT '单次剂量' AFTER item_name,
    ADD COLUMN dose_unit VARCHAR(16) NULL COMMENT '剂量单位' AFTER single_dose,
    ADD COLUMN frequency VARCHAR(8) NULL COMMENT '频次 qd/bid/tid/q8h' AFTER dose_unit;

CREATE TABLE IF NOT EXISTS demo_pharmacy.drug_dict (
    drug_code VARCHAR(32) PRIMARY KEY COMMENT '药品编码',
    drug_name VARCHAR(128) NOT NULL,
    max_daily_dose DECIMAL(8,2) NULL COMMENT '日最大剂量（NULL=无上限）',
    dose_unit VARCHAR(16) NOT NULL COMMENT '剂量单位 g/mg/ml/单位',
    allergen VARCHAR(64) NULL COMMENT '含过敏原（青霉素/头孢…）',
    child_forbidden CHAR(1) NOT NULL DEFAULT 'N' COMMENT '儿童禁用 Y/N'
) COMMENT '药品知识字典：处方审核（剂量上限/过敏禁忌/儿童禁用）的知识源';

CREATE TABLE IF NOT EXISTS demo_emr.patient_allergy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inhos_no VARCHAR(32) NOT NULL COMMENT '住院号',
    allergen VARCHAR(64) NOT NULL COMMENT '过敏原',
    severity VARCHAR(16) NOT NULL DEFAULT '一般' COMMENT '严重程度 一般/严重',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_allergy_inhos (inhos_no)
) COMMENT '患者过敏史（EMR 侧，与 HIS 医嘱跨库割裂，本体层拉通校验）';
