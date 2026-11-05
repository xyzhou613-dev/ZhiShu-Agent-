-- =============================================================
-- V32: 问数澄清任务流（D2b）——UNANSWERABLE 断链补全：
--      缺什么(reason) → 对应什么对象(本表任务) → 去哪里补(追问用户) → 补齐后从哪继续(origin_question+supplement 重入路由)。
--      分型：仅 A 型歧义（gapType=AMBIGUITY，计划阶段 LLM 结构化输出）建任务；
--      V 型词表缺口/P 型平台故障维持现状回流 bm_ontology_miss。
--      rounds 语义=已发出的追问轮数（创建时显式置 1，列 DEFAULT 0 仅为兼容规格稿）。
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_clarify_task (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    scene            VARCHAR(16)  NOT NULL COMMENT 'ANALYTICS（MVP 仅此值；CS 预留）',
    origin_question  VARCHAR(512) NOT NULL COMMENT '原问题（resume 锚点）',
    reason           VARCHAR(512) NULL COMMENT 'UNANSWERABLE reason（LLM 原话）',
    gap_type         VARCHAR(16)  NOT NULL COMMENT '仅 AMBIGUITY 会建任务，列保留以便扩展',
    clarify_question VARCHAR(512) NOT NULL COMMENT '追问话术：LLM 生成，无 Key 降级模板拼装',
    status           VARCHAR(16)  NOT NULL COMMENT 'PENDING / RESOLVED / GAVE_UP',
    supplement       VARCHAR(1024) NULL COMMENT '用户补充原文（多轮以；拼接，RESOLVED/GAVE_UP 后保留作证据）',
    rounds           INT NOT NULL DEFAULT 0 COMMENT '已发出的追问轮数，上限 2',
    miss_id          BIGINT NULL COMMENT 'GAVE_UP 时关联回流的 bm_ontology_miss.id',
    created_at       DATETIME NULL,
    answered_at      DATETIME NULL,
    closed_at        DATETIME NULL,
    KEY idx_clarify_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问数澄清任务（可挂起可续跑）';
