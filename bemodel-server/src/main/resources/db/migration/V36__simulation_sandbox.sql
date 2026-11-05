-- =============================================================
-- V36: 推演沙盘——运行记录 bm_simulation_run + 传导链映射补缺 + 沙盘观察探针
--      形制同 V35 bm_lab_run；payload 完成时一次写。
--      映射补缺只补物理列真实存在的缺口(stock_out.drug_code,V12 建表已有此列),不造映射;
--      stock_in.drug_code 物理列虽在但有意不补——它是本次演示的活体 BLOCKED_GAP 断链样本。
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_simulation_run (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    scenario     VARCHAR(32)   NOT NULL COMMENT '场景键 STOCK_CUT',
    status       VARCHAR(20)   NOT NULL COMMENT 'QUEUED/RUNNING/DONE/FAILED',
    payload_json MEDIUMTEXT             COMMENT '施加+波及步骤+观察diff 整体一次写',
    error_msg    VARCHAR(1000)          COMMENT '整体失败原因',
    created_at   DATETIME      NOT NULL COMMENT '起跑时间',
    finished_at  DATETIME               COMMENT 'DONE/FAILED 落定时间',
    KEY idx_sim_run_scenario (scenario),
    KEY idx_sim_run_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='推演沙盘运行记录';

-- 传导链补缺:出库单的药品编码(属性中文名与 DRUG_STOCK.drug_code 同为「药品编码」,键族接力第一跳)
INSERT INTO bm_attribute (concept_code, attr_code, attr_name, data_type, is_key, definition, sort) VALUES
('STOCK_OUT', 'drug_code', '药品编码', 'STRING', 0, '出库药品编码,与库存/医嘱同一药品身份', 4);

INSERT INTO bm_mapping (ds_code, table_name, column_name, concept_code, attr_code, value_map, confirmed, source, status) VALUES
('DS_PHARMACY', 'stock_out', 'drug_code', 'STOCK_OUT', 'drug_code', NULL, 1, 'MANUAL', 'ACTIVE');

-- 沙盘观察探针(观察面只读执行 probe_sql 于 DS_LAB,不回写 last_val——主库零写入红线)
INSERT INTO bm_metric (metric_code, name, definition, formula, concept_code, owner, ds_code, probe_sql, warn_threshold) VALUES
('LOW_STOCK_COUNT', '低库存药品数', '当前库存不足 50 盒的药品个数(演示口径)', 'COUNT(库存数量≤50 的药品)', 'DRUG_STOCK', '孙药学', 'DS_LAB',
 'SELECT COUNT(*) FROM drug_stock WHERE quantity <= 50', 0),
('STOCK_TURNOVER', '库存周转倍数', '累计出库量÷当前库存总量,越大说明周转越快(演示口径)', 'Σ出库数量 ÷ Σ库存数量', 'DRUG_STOCK', '孙药学', 'DS_LAB',
 'SELECT ROUND((SELECT IFNULL(SUM(quantity),0) FROM stock_out) / NULLIF((SELECT IFNULL(SUM(quantity),0) FROM drug_stock),0), 2)', NULL);
