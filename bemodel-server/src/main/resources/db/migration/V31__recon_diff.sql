-- =============================================================
-- V31: 跨库核对语义化——差异显式登记表（D2）
--      借鉴 drugmodel 失败三表模式：核对差异不是只抛文案，落库为可查数据对象；
--      结论从二值（违规/滞留）升级为语义档：违规 / 中间态 / 正常差异 / 归因消除，
--      每档带归因说明与建议动作（参照"收费未取药属正常差异必须显式登记否则误判"教训）。
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_recon_diff (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id           VARCHAR(40)  NOT NULL COMMENT '核对批次号',
    direction        VARCHAR(8)   NOT NULL COMMENT 'A=发药×缴费 B=缴费×发药',
    state_code       VARCHAR(40)  NOT NULL COMMENT '语义档：VIOLATION_PREPAY_BYPASS/RETURNED_PENDING_REFUND/STUCK_BACKLOG/IN_FLIGHT/NIGHTLY_CANCEL/CANCELLED_AFTER_PAY',
    severity         VARCHAR(16)  NOT NULL COMMENT 'VIOLATION/WATCH/NORMAL',
    attribution      VARCHAR(256) NULL COMMENT '归因说明',
    suggested_action VARCHAR(256) NULL COMMENT '建议动作',
    patient_no       VARCHAR(64)  NULL COMMENT '业务键（保留可下钻，不脱敏）',
    order_id         VARCHAR(64)  NULL,
    item_name        VARCHAR(128) NULL,
    created_at       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    KEY idx_recon_run (run_id),
    KEY idx_recon_state (state_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨库核对差异登记';
