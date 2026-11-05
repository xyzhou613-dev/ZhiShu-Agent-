-- =============================================================
-- V30: 映射生命周期（C：产品化分水岭）
--      1) bm_mapping 增加 status：PROPOSED(AI/人工提议) → ACTIVE(生效) → DEPRECATED(停用)
--         存量口径：confirmed=0 → PROPOSED，其余（confirmed=1/NULL 种子）→ ACTIVE
--      2) bm_mapping_log 修改留痕：CREATE/UPDATE/TRANSITION/DELETE 前后对照
--         （借鉴主数据治理闭环：被引用的数据可停用不可静默改写，改动可审计）
-- =============================================================

ALTER TABLE bm_mapping
    ADD COLUMN status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '生命周期: PROPOSED/ACTIVE/DEPRECATED',
    ADD COLUMN updated_by VARCHAR(64)  NULL COMMENT '最近操作人',
    ADD COLUMN updated_at DATETIME     NULL COMMENT '最近操作时间';

UPDATE bm_mapping SET status = 'PROPOSED' WHERE confirmed = 0;

CREATE TABLE IF NOT EXISTS bm_mapping_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    mapping_id  BIGINT       NOT NULL COMMENT 'bm_mapping.id',
    action      VARCHAR(16)  NOT NULL COMMENT 'CREATE/UPDATE/TRANSITION/DELETE',
    before_json TEXT         NULL COMMENT '变更前快照',
    after_json  TEXT         NULL COMMENT '变更后快照',
    operator    VARCHAR(64)  NULL COMMENT '操作人',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    KEY idx_mapping_log_mid (mapping_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='映射变更留痕';
