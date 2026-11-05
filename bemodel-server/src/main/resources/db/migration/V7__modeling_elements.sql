-- =============================================================
-- V7: 本体六要素完备化 + 统一版本发布 + LLM调用审计
-- 名词=bm_concept / 动词=bm_relation / 属性=bm_attribute（已有）
-- 规则=bm_rule / 动作=bm_action（新增）
-- 版本=bm_release（快照式发布） / LLM审计=bm_llm_log（调用绑定本体版本）
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_rule (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_code   VARCHAR(64) NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    concept_code VARCHAR(64) NOT NULL COMMENT '约束的概念',
    rule_type   VARCHAR(16) NOT NULL COMMENT '约束/推导/校验',
    expression  TEXT NOT NULL COMMENT '规则的声明式表达（业务语言）',
    metric_code VARCHAR(64) COMMENT '关联的可执行指标（规则→探针）',
    severity    VARCHAR(8) DEFAULT '中' COMMENT '高/中/低',
    owner       VARCHAR(64),
    status      VARCHAR(16) DEFAULT 'DRAFT',
    version     INT DEFAULT 1,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='业务规则（声明式）';

CREATE TABLE IF NOT EXISTS bm_action (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    action_code  VARCHAR(64) NOT NULL UNIQUE,
    name         VARCHAR(128) NOT NULL COMMENT '动作动词，如：取消医嘱',
    concept_code VARCHAR(64) NOT NULL COMMENT '作用对象概念',
    from_status  VARCHAR(64) COMMENT '前置状态（空=无前置）',
    to_status    VARCHAR(64) NOT NULL COMMENT '目标状态',
    trigger_desc VARCHAR(256) COMMENT '触发方/触发条件',
    description  VARCHAR(512),
    status       VARCHAR(16) DEFAULT 'DRAFT',
    version      INT DEFAULT 1,
    created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='业务动作（概念状态机）';

CREATE TABLE IF NOT EXISTS bm_release (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    version_tag   VARCHAR(32) NOT NULL UNIQUE COMMENT '版本号 v1.0/v1.1...',
    change_summary VARCHAR(512),
    element_count INT COMMENT '快照元素总数',
    snapshot_json LONGTEXT COMMENT '发布时全部已发布元素的快照',
    released_by   VARCHAR(64),
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='本体版本发布';

CREATE TABLE IF NOT EXISTS bm_llm_log (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    call_type        VARCHAR(32) COMMENT 'MAPPING_SUGGEST/SEARCH_ANSWER/RCA_REPORT/IMPACT_ADVICE',
    model            VARCHAR(64),
    ontology_version VARCHAR(32) COMMENT '调用时使用的本体版本',
    prompt_digest    VARCHAR(256) COMMENT '提示词摘要',
    latency_ms       BIGINT,
    success          TINYINT,
    err_msg          VARCHAR(512),
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_type (call_type),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='LLM调用审计';

-- ---------- 规则种子（声明式业务规则，部分绑定可执行指标） ----------
INSERT INTO bm_rule (rule_code, name, concept_code, rule_type, expression, metric_code, severity, owner, status, version) VALUES
('RULE-FEE-001', '取消医嘱不得计费', 'FEE_DETAIL', '约束', '医嘱状态=已取消 的明细，其费用状态必须=已退费，不允许存在正常费用。', 'CANCEL_NOT_REFUND', '高', '陈财务', 'PUBLISHED', 1),
('RULE-FEE-002', '检验撤销必须退费', 'LAB_APPLY', '约束', '检验申请状态=已撤销 时，关联费用必须完成退费（退款流水可查）。', 'CANCEL_NOT_REFUND', '高', '陈财务', 'PUBLISHED', 1),
('RULE-LAB-001', '已发布申请必有报告', 'LAB_APPLY', '校验', '检验申请状态=已发布 的申请单，必须存在对应检验报告。', NULL, '中', '张医技', 'PUBLISHED', 1),
('RULE-PHARM-001', '先退药后退费', 'DISPENSE', '约束', '药品医嘱取消时，发药记录必须先置为已退药，费用方可退费。', NULL, '中', '孙药学', 'PUBLISHED', 1),
('RULE-SETTLE-001', '结算总额口径', 'SETTLEMENT', '推导', '结算总额 = Σ(费用状态=正常 的费用明细金额)。', NULL, '高', '陈财务', 'PUBLISHED', 1),
('RULE-OPD-001', '门诊先缴费后执行', 'MEDICAL_ORDER', '约束', '门诊处方缴费完成前，不允许执行（发药/采样）。', NULL, '高', '陈财务', 'REVIEW', 1);

-- ---------- 动作种子（概念状态机：动词 + 状态跃迁） ----------
INSERT INTO bm_action (action_code, name, concept_code, from_status, to_status, trigger_desc, description, status, version) VALUES
('ACT-ORDER-CREATE', '开立医嘱', 'MEDICAL_ORDER', NULL, '未执行', '医生在医生站开立', '医嘱生命周期起点', 'PUBLISHED', 1),
('ACT-ORDER-EXEC', '执行医嘱', 'MEDICAL_ORDER', '未执行', '已执行', '护士站/医技科室执行确认', '触发计费与执行环节', 'PUBLISHED', 1),
('ACT-ORDER-CANCEL', '取消医嘱', 'MEDICAL_ORDER', '未执行,已执行', '已取消', '医生取消', '必须联动退费（见RULE-FEE-001）', 'PUBLISHED', 1),
('ACT-APPLY-CANCEL', '撤销检验申请', 'LAB_APPLY', '已采样', '已撤销', '检验科撤销或医嘱取消联动', 'v5.2后状态码为C（旧码X）', 'PUBLISHED', 2),
('ACT-REPORT-PUBLISH', '发布检验报告', 'LAB_REPORT', NULL, '已发布', '检验师审核发布', '报告状态：正常/异常', 'PUBLISHED', 1),
('ACT-DISPENSE', '发药', 'DISPENSE', '已调剂', '已发药', '药房药师核发', '药品闭环执行点', 'PUBLISHED', 1),
('ACT-RETURN-DRUG', '退药', 'DISPENSE', '已发药', '已退药', '医嘱取消联动', '退药完成后方可退费', 'PUBLISHED', 1),
('ACT-SETTLE', '出院结算', 'INP_VISIT', '在院', '出院', '收费处结算', '汇总全部费用明细', 'PUBLISHED', 1),
('ACT-REFUND', '退费', 'FEE_DETAIL', '正常', '已退费', '适配器自动或人工发起', '退费须有审计流水', 'PUBLISHED', 1);

-- ---------- 初始版本 v1.0（快照由应用启动后通过发布接口生成，此处仅占位历史记录说明） ----------
-- 说明：首个版本通过 POST /api/release/publish 生成，保证快照与运行时元数据严格一致。
