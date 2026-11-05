-- =============================================================
-- V4: 指标监控能力 —— 指标可绑定实测探针SQL与告警阈值
-- =============================================================

ALTER TABLE bm_metric
    ADD COLUMN ds_code         VARCHAR(64)  COMMENT '探针执行数据源',
    ADD COLUMN probe_sql       TEXT         COMMENT '实测SQL（须返回单个数值）',
    ADD COLUMN warn_threshold  INT          COMMENT '告警阈值（实测值>阈值即告警）',
    ADD COLUMN last_val        INT          COMMENT '最近实测值',
    ADD COLUMN last_eval_at    DATETIME     COMMENT '最近实测时间';

-- 取消未退费笔数：案例暴露的计费一致性核心监控指标
UPDATE bm_metric SET
    ds_code = 'DS_HIS',
    probe_sql = 'SELECT COUNT(*) FROM fee_detail f JOIN medical_order o ON f.order_id = o.order_id WHERE o.order_status = ''2'' AND f.fee_status = ''1''',
    warn_threshold = 0
WHERE metric_code = 'CANCEL_NOT_REFUND';

-- 出院人数：按结算口径实测
UPDATE bm_metric SET
    ds_code = 'DS_CHARGE',
    probe_sql = 'SELECT COUNT(DISTINCT inhos_no) FROM settlement WHERE settle_status = ''1''',
    warn_threshold = NULL
WHERE metric_code = 'DISCHARGE_COUNT';

-- 检验撤销率（实测为撤销申请数，监控绝对量）
UPDATE bm_metric SET
    ds_code = 'DS_LIS',
    probe_sql = 'SELECT COUNT(*) FROM lab_apply WHERE apply_status IN (''C'', ''X'')',
    warn_threshold = NULL
WHERE metric_code = 'LAB_CANCEL_RATE';

-- 出院患者均次费用
UPDATE bm_metric SET
    ds_code = 'DS_CHARGE',
    probe_sql = 'SELECT IFNULL(ROUND(SUM(total_amount)/COUNT(DISTINCT inhos_no)),0) FROM settlement WHERE settle_status = ''1''',
    warn_threshold = NULL
WHERE metric_code = 'AVG_INP_FEE';
