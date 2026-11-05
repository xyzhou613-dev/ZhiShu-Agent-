-- =============================================================
-- V34: 本体提案生成器（P1a）——分层自治 L2：AI 提议、人裁决。
--     miss 池只覆盖采集，AI 归类要逐条点；本表把「候选卡 + 近义预审 +
--     聚类归并」批量产物落成提案队列，裁决仍复用 miss 采纳机制
--     （NEW_CONCEPT→DRAFT 草稿，ATTACH_TERM→挂方言术语），AI 不发布任何东西。
--     运行记录对位 bm_inspect_run；全失败诚实留痕（LLM 不可用不编提案）。
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_proposal_run (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    scanned     INT NOT NULL COMMENT '候选 miss 数（选样后）',
    generated_cnt INT NOT NULL COMMENT '生成提案数（同判定归并后）',
    llm_failed  INT NOT NULL COMMENT 'LLM 失败/判定失效丢弃的 miss 数',
    message     VARCHAR(255) NULL COMMENT '本次运行说明（全失败时给原因）',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提案生成运行记录';

CREATE TABLE IF NOT EXISTS bm_ontology_proposal (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    primary_miss_id     BIGINT       NOT NULL COMMENT '主信号 miss id（组内 count 最高）',
    miss_ids            JSON         NOT NULL COMMENT '聚类归并的全部 miss id（含主信号）',
    term                VARCHAR(128) NOT NULL COMMENT '代表说法（主信号原文）',
    signal_count        INT NOT NULL DEFAULT 1 COMMENT '信号总热度（组内 count 求和）',
    action              VARCHAR(16)  NOT NULL COMMENT 'AI 预审判定：NEW_CONCEPT / ATTACH_TERM',
    target_concept_code VARCHAR(64)  NULL COMMENT 'ATTACH_TERM 的挂靠目标概念编码',
    suggestion_json     JSON         NULL COMMENT '候选卡：code/name/domainCode/definition/reason/confidence/warnings',
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ADOPTED / REJECTED',
    decided_by          VARCHAR(64)  NULL COMMENT '裁决人',
    decided_at          DATETIME     NULL COMMENT '裁决时间',
    reject_reason       VARCHAR(255) NULL COMMENT '驳回理由',
    run_id              BIGINT       NOT NULL COMMENT '生成批次 bm_proposal_run.id',
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_proposal_status (status),
    KEY idx_proposal_miss (primary_miss_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 提案队列（本体增长回路 L2）';
