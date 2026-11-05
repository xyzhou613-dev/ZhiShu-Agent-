-- =============================================================
-- V19: 公理结构化（借鉴 Utopia）—— 关系携带机器可查公理、概念互斥表、
--      IRI 与平台 code 分离（OWL 导入按 IRI 对齐，code 只做平台内部键）
-- =============================================================

-- ---------- 1) bm_relation：结构公理（对称/传递/函数/逆函数/反对称/互逆/IRI） ----------
-- inverse_of 存放互逆关系的 relation_name（bm_relation 无独立 code 列，唯一键为 from+to+name）
ALTER TABLE bm_relation
    ADD COLUMN is_symmetric          TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '对称公理：A→B 蕴含 B→A',
    ADD COLUMN is_transitive         TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '传递公理：A→B→C 蕴含 A→C（partOf/属于类）',
    ADD COLUMN is_functional         TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '函数公理：同一主语至多一个宾语',
    ADD COLUMN is_inverse_functional TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逆函数公理：同一宾语至多一个主语',
    ADD COLUMN is_asymmetric         TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '反对称公理：A→B 禁止 B→A',
    ADD COLUMN inverse_of            VARCHAR(64) NULL COMMENT '互逆关系的 relation_name',
    ADD COLUMN iri                   VARCHAR(255) NULL COMMENT '外部本体 IRI（OWL 导入对齐用，与平台内部键分离）',
    ADD UNIQUE KEY uk_relation_iri (iri);

-- ---------- 2) 概念互斥表（公理结构化：互斥语义机器可查，双向查询由服务层负责） ----------
CREATE TABLE IF NOT EXISTS bm_concept_disjoint (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    concept_a_code VARCHAR(64) NOT NULL COMMENT '概念A编码',
    concept_b_code VARCHAR(64) NOT NULL COMMENT '概念B编码',
    definition     TEXT COMMENT '互斥理由（来源公理/业务口径）',
    status         VARCHAR(16) DEFAULT 'PUBLISHED' COMMENT 'DRAFT/REVIEW/PUBLISHED/DEPRECATED',
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_disjoint_pair (concept_a_code, concept_b_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='概念互斥公理（对称语义，服务层双向查询）';

-- ---------- 3) bm_concept：IRI 与 code 分离（唯一索引允许 NULL） ----------
ALTER TABLE bm_concept
    ADD COLUMN iri VARCHAR(255) NULL COMMENT '外部本体 IRI（OWL 导入对齐用）',
    ADD UNIQUE KEY uk_concept_iri (iri);
