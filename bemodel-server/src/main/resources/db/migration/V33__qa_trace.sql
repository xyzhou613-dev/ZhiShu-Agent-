-- =============================================================
-- V33: 证据链 trace 页（D3）——问数答案证据持久化。
--     drugmodel 教训：证据链不可断尾（webui refs 指向站外不可点击）；
--      本表把每次语义查询的「问题→计划→校验→执行→作答」五段证据落库，
--      trace_id 可在 /trace 页逐段还原，锚点全部平台内可达。
--      MVP 只记 QUERY 成功路径（MODEL_ANSWER/失败路径不落，避免噪声）。
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_qa_trace (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id         VARCHAR(24)  NOT NULL COMMENT 'QA-<时间戳>-<rand6>',
    scene            VARCHAR(16)  NOT NULL COMMENT 'ANALYTICS / CS',
    question         VARCHAR(512) NOT NULL COMMENT '用户原问题',
    ds_code          VARCHAR(32)  NULL COMMENT '执行数据源',
    used_tables      VARCHAR(256) NULL COMMENT '实际用到的物理表（逗号分隔）',
    sql_text         VARCHAR(1024) NULL COMMENT '白名单校验后实际执行的 SQL',
    semantics        VARCHAR(512) NULL COMMENT '语义计划说明（查了什么、用了哪些概念）',
    matched_concepts VARCHAR(256) NULL COMMENT '命中的 PUBLISHED 概念 code（逗号分隔）',
    row_count        INT NULL COMMENT '真实返回行数',
    answer           TEXT NULL COMMENT '最终结论',
    answer_source    VARCHAR(16)  NOT NULL COMMENT 'LLM / TEMPLATE（诚实标注作答来源）',
    duration_ms      INT NULL COMMENT 'SQL 执行耗时',
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_qatrace_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问数答案证据链';
