-- =============================================================
-- V35: AI 对比实验室(Plan 2)——三臂运行记录 bm_lab_run。
--     A(AI+本体)/B(AI+裸SQL)/C(传统固定功能) 同题三臂,异步执行;
--     轨迹/锚点/耗时整体落 payload_json,沙箱业务数据不进平台库。
--     对位 bm_gov_scan / bm_proposal_run 的运行记录样式。
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_lab_run (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_key VARCHAR(32)   NOT NULL COMMENT '实验键 GATE/ADVERSARIAL/REFUND/TRAVERSE',
    question       VARCHAR(500)  NOT NULL COMMENT '三臂同题的问题文本',
    force_execute  TINYINT(1)    NOT NULL DEFAULT 1 COMMENT 'B 臂强制执行开关(允许写 SQL)',
    status         VARCHAR(20)   NOT NULL COMMENT 'QUEUED/RUNNING/DONE/FAILED',
    payload_json   MEDIUMTEXT             COMMENT '三臂结果:A/B/C 各自 status+answer+steps+llmCalls+elapsedMs+anchorsCited',
    error_msg      VARCHAR(1000)          COMMENT '整体失败原因',
    created_at     DATETIME      NOT NULL COMMENT '起跑时间',
    finished_at    DATETIME               COMMENT 'DONE/FAILED 落定时间',
    KEY idx_lab_run_exp (experiment_key),
    KEY idx_lab_run_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='AI 对比实验室运行记录';
