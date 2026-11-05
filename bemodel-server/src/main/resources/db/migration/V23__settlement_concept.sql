-- =============================================================
-- V23: 结算概念语义补全 —— SETTLEMENT 概念/映射在 V2/V5 已部分存在，
--      本迁移补齐缺口：单患者单次住院口径写入定义 + 结算状态属性与映射
--      （settle_status 带枚举值典，供语义查询把「已冲红」反解为 '2'）
-- =============================================================

-- 口径写入定义：一张结算单对应一个患者的一次住院，不支持跨患者合并结算
UPDATE bm_concept SET definition =
    '患者出院时对整个住院费用的汇总结算。一张结算单对应一个患者的一次住院（按 inhos_no 维系），不支持多患者合并结算。'
WHERE code = 'SETTLEMENT';

-- 缺失属性：结算状态（ENUM）
INSERT INTO bm_attribute (concept_code, attr_code, attr_name, data_type, is_key, definition, sort) VALUES
('SETTLEMENT', 'settle_status', '结算状态', 'ENUM', 0, '1已结算 2已冲红', 4);

-- 缺失映射：DS_CHARGE.settlement.settle_status（带枚举值典）
INSERT INTO bm_mapping (ds_code, table_name, column_name, concept_code, attr_code, value_map, confirmed, source) VALUES
('DS_CHARGE', 'settlement', 'settle_status', 'SETTLEMENT', 'settle_status', '{"1":"已结算","2":"已冲红"}', 1, 'MANUAL');
