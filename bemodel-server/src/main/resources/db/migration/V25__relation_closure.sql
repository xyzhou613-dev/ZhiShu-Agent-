-- =============================================================
-- V25: 传递闭包物化 —— 发布时对 is_transitive=1 的关系算传递闭包落表
--      （最小成本真推理：推理在发布时，查询零推理成本）
-- =============================================================

CREATE TABLE IF NOT EXISTS bm_relation_closure (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    relation_name VARCHAR(64) NOT NULL COMMENT '传递关系名（如 属于）',
    from_concept  VARCHAR(64) NOT NULL,
    to_concept    VARCHAR(64) NOT NULL,
    depth         INT         NOT NULL COMMENT '最短路径跳数',
    UNIQUE KEY uk_closure (relation_name, from_concept, to_concept)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='传递关系闭包（发布时物化）';

-- 传递链种子：与既有 VITAL_SIGN —属于→ EMR_RECORD 拼成 ≥3 节点链
-- NURSE_EXEC —属于→ NURSING_PLAN —属于→ EMR_RECORD（执行确认归入护理计划，护理计划是病案组成部分）
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description, is_transitive) VALUES
('NURSING_PLAN', 'EMR_RECORD', '属于', '护理计划是病案组成部分', 1),
('NURSE_EXEC', 'NURSING_PLAN', '属于', '执行确认记录归入护理计划', 1);
