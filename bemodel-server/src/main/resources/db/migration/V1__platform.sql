-- =============================================================
-- V1: BeModel 平台库表结构（默认 schema = bemodel）
-- 本体治理域 + 数据源绑定域 + 链路追溯域 + 根因分析域
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_domain (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    code        VARCHAR(64)  NOT NULL UNIQUE COMMENT '域编码',
    name        VARCHAR(128) NOT NULL COMMENT '域名称',
    description VARCHAR(512) COMMENT '域说明',
    sort        INT DEFAULT 0,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='业务域';

CREATE TABLE IF NOT EXISTS bm_concept (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    code        VARCHAR(64)  NOT NULL UNIQUE COMMENT '概念编码（全局唯一）',
    name        VARCHAR(128) NOT NULL COMMENT '标准名称',
    domain_code VARCHAR(64)  NOT NULL COMMENT '所属域',
    definition  TEXT COMMENT '业务定义',
    owner       VARCHAR(64) COMMENT '口径负责人',
    status      VARCHAR(16) DEFAULT 'DRAFT' COMMENT 'DRAFT/REVIEW/PUBLISHED/DEPRECATED',
    version     INT DEFAULT 1,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='业务概念';

CREATE TABLE IF NOT EXISTS bm_attribute (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    concept_code VARCHAR(64) NOT NULL,
    attr_code    VARCHAR(64) NOT NULL COMMENT '属性编码',
    attr_name    VARCHAR(128) NOT NULL COMMENT '属性名称',
    data_type    VARCHAR(32) NOT NULL COMMENT 'STRING/NUMBER/DATE/ENUM',
    is_key       TINYINT DEFAULT 0 COMMENT '是否主键语义',
    definition   VARCHAR(512) COMMENT '业务含义',
    sort         INT DEFAULT 0,
    UNIQUE KEY uk_concept_attr (concept_code, attr_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='概念属性';

CREATE TABLE IF NOT EXISTS bm_relation (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    from_concept  VARCHAR(64) NOT NULL,
    to_concept    VARCHAR(64) NOT NULL,
    relation_name VARCHAR(64) NOT NULL COMMENT '关系名，如：产生/生成/结算',
    description   VARCHAR(512),
    UNIQUE KEY uk_rel (from_concept, to_concept, relation_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='概念关系（本体图的边）';

CREATE TABLE IF NOT EXISTS bm_term (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    term           VARCHAR(128) NOT NULL COMMENT '术语/方言叫法',
    concept_code   VARCHAR(64)  NOT NULL COMMENT '挂靠的标准概念',
    source_product VARCHAR(64) COMMENT '来源产品，如 HIS/LIS',
    term_type      VARCHAR(16) DEFAULT 'ALIAS' COMMENT 'STANDARD/ALIAS',
    UNIQUE KEY uk_term (term, source_product)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='业务术语库';

CREATE TABLE IF NOT EXISTS bm_metric (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    metric_code VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    definition  TEXT COMMENT '口径定义',
    formula     VARCHAR(512) COMMENT '计算公式',
    concept_code VARCHAR(64) COMMENT '绑定概念',
    owner       VARCHAR(64),
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='指标口径库';

CREATE TABLE IF NOT EXISTS bm_datasource (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    ds_code      VARCHAR(64) NOT NULL UNIQUE COMMENT '数据源编码',
    ds_name      VARCHAR(128) NOT NULL,
    product_name VARCHAR(64) COMMENT '所属产品线',
    db_type      VARCHAR(16) DEFAULT 'MYSQL',
    host         VARCHAR(128) NOT NULL,
    port         INT NOT NULL,
    db_name      VARCHAR(64) NOT NULL,
    username     VARCHAR(64) NOT NULL,
    password     VARCHAR(128) NOT NULL,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='产品库数据源';

CREATE TABLE IF NOT EXISTS bm_physical_table (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    ds_code       VARCHAR(64) NOT NULL,
    table_name    VARCHAR(128) NOT NULL,
    table_comment VARCHAR(512),
    scanned_at    DATETIME,
    UNIQUE KEY uk_ds_table (ds_code, table_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='物理表快照';

CREATE TABLE IF NOT EXISTS bm_physical_column (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    ds_code          VARCHAR(64) NOT NULL,
    table_name       VARCHAR(128) NOT NULL,
    column_name      VARCHAR(128) NOT NULL,
    data_type        VARCHAR(64),
    column_comment   VARCHAR(512),
    is_pk            TINYINT DEFAULT 0,
    ordinal_position INT DEFAULT 0,
    UNIQUE KEY uk_ds_col (ds_code, table_name, column_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='物理列快照';

CREATE TABLE IF NOT EXISTS bm_mapping (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    ds_code      VARCHAR(64)  NOT NULL,
    table_name   VARCHAR(128) NOT NULL,
    column_name  VARCHAR(128) NOT NULL,
    concept_code VARCHAR(64)  NOT NULL,
    attr_code    VARCHAR(64)  NOT NULL,
    value_map    VARCHAR(1024) COMMENT '值字典映射JSON，如 {"2":"已取消"}',
    confirmed    TINYINT DEFAULT 0 COMMENT '是否人工确认',
    source       VARCHAR(16) DEFAULT 'MANUAL' COMMENT 'MANUAL/AI',
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_map (ds_code, table_name, column_name, concept_code, attr_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='物理列-概念属性映射';

CREATE TABLE IF NOT EXISTS link_node (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    node_type    VARCHAR(16) NOT NULL COMMENT 'TICKET/REQUIREMENT/CHANGE/TESTCASE/DEPLOY',
    ref_no       VARCHAR(64) NOT NULL UNIQUE COMMENT '单号',
    title        VARCHAR(256) NOT NULL,
    concept_code VARCHAR(64) NOT NULL COMMENT '挂靠概念（链路中枢）',
    status       VARCHAR(32),
    occurred_at  DATETIME,
    payload      TEXT COMMENT '扩展字段JSON',
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_concept (concept_code),
    KEY idx_type (node_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='链路节点（需求-研发-测试-运维-客服）';

CREATE TABLE IF NOT EXISTS rca_case (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_no      VARCHAR(64) NOT NULL UNIQUE,
    ticket_ref   VARCHAR(64) NOT NULL COMMENT '来源客服工单',
    concept_code VARCHAR(64) NOT NULL,
    status       VARCHAR(16) DEFAULT 'RUNNING' COMMENT 'RUNNING/DONE/FAILED',
    conclusion   TEXT,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    finished_at  DATETIME
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='根因分析案例';

CREATE TABLE IF NOT EXISTS rca_step (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_id     BIGINT NOT NULL,
    step_no     INT NOT NULL,
    step_name   VARCHAR(128) NOT NULL,
    step_type   VARCHAR(16) NOT NULL COMMENT 'TRAVERSE/PROBE/LINK/REPORT',
    sql_text    TEXT COMMENT '实际执行的SQL',
    hit_count   INT DEFAULT 0,
    result_json TEXT COMMENT '探针结果快照JSON',
    status      VARCHAR(16) DEFAULT 'SUCCESS',
    started_at  DATETIME,
    finished_at DATETIME,
    KEY idx_case (case_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='根因分析步骤（证据链）';

CREATE TABLE IF NOT EXISTS rca_report (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_id          BIGINT NOT NULL UNIQUE,
    root_cause       TEXT COMMENT '根因结论',
    evidence_json    TEXT COMMENT '证据链JSON',
    impact_json      TEXT COMMENT '影响面JSON',
    suggestions_json TEXT COMMENT '整改建议JSON',
    llm_used         TINYINT DEFAULT 0 COMMENT '报告是否由LLM生成',
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='根因分析报告';
