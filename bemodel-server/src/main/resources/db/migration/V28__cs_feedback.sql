-- =============================================================
-- V28: 客服路由反馈回路 —— 问答归类的对错评议，
--      错例（correct=0）回流进 CS_ROUTE 提示词让路由模型看到前车之鉴
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_cs_feedback (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    question   VARCHAR(512) NOT NULL COMMENT '用户原始问题',
    intent     VARCHAR(32)  COMMENT '当时命中的意图标签',
    router     VARCHAR(16)  COMMENT 'LLM/RULE/SEMANTIC/NONE',
    correct    TINYINT(1)   NOT NULL COMMENT '1 归类正确 / 0 错误',
    comment    VARCHAR(512) NULL COMMENT '备注（可写正确意图）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_feedback_correct (correct, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='客服问答路由反馈';
