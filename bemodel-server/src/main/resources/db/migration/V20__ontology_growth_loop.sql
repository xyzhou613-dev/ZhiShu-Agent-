-- =============================================================
-- V20: 本体增长回路 —— 收集"词表外说法"（搜索零命中 / AI 映射失败）
--      为本体扩展提案池：人工审核采纳/忽略。
--      忽略是标记不是删除（计数照涨、可撤回）；
--      采纳只创建 DRAFT 概念（不直接发布）；撤销不删数据（概念置 DEPRECATED）
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_ontology_miss (
    id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
    term                 VARCHAR(128) NOT NULL COMMENT '未命中的说法原文（搜索查询词 / 物理列注释或列名）',
    kind                 VARCHAR(16)  NOT NULL COMMENT 'CONCEPT/ATTRIBUTE',
    source               VARCHAR(16)  NOT NULL COMMENT 'SEARCH/MAPPING_AI',
    count                INT          NOT NULL DEFAULT 1 COMMENT '累计出现次数（忽略后照涨）',
    dismissed            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '已忽略（标记，不删除）',
    dismiss_reason       VARCHAR(255) NULL COMMENT '忽略理由',
    adopted_concept_code VARCHAR(64)  NULL COMMENT '采纳后落的概念 code',
    revoked              TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '采纳被撤销（概念置 DEPRECATED，miss 回待处理池）',
    first_seen           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次出现',
    last_seen            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近出现',
    UNIQUE KEY uk_miss_term (term, kind),
    KEY idx_miss_board (dismissed, count)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='本体增长回路：词表外说法采集池';
