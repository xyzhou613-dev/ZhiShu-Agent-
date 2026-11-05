-- =============================================================
-- V27: 指标定时巡检 + 平台内告警（不接外部 webhook）
--      告警幂等：同一指标存在未读告警时不重复生成（防告警轰炸）
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_alert_notice (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    metric_code  VARCHAR(64)  NOT NULL COMMENT '越限指标编码',
    metric_name  VARCHAR(128) COMMENT '指标名称',
    actual_value INT          NULL COMMENT '实测值（探针失败为 NULL）',
    threshold    INT          NULL COMMENT '告警阈值',
    message      VARCHAR(512) COMMENT '告警文案',
    status       VARCHAR(8)   NOT NULL DEFAULT '未读' COMMENT '未读/已读',
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_notice_status (status, metric_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='指标巡检平台内告警';

CREATE TABLE IF NOT EXISTS bm_inspect_run (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    evaluated       INT NOT NULL COMMENT '本次评估指标数',
    alarmed         INT NOT NULL COMMENT '越限/异常数',
    notices_created INT NOT NULL COMMENT '新生成告警数（幂等去重后）',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='巡检执行记录';
