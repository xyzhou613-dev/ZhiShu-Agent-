-- =============================================================
-- V29: 评审员角色与发布审批（P0②收尾）
--      1) bm_user 角色枚举扩至 ADMIN/EDITOR/REVIEWER/VIEWER，
--         补评审员演示账号 reviewer/reviewer123（仅演示环境，与 V26 同一口径）
--      2) bm_release 增加审批人列：发布快照必须记录谁批准了这版口径
--         （此前 bm_release 只有 releasedBy，无审批留痕）
-- =============================================================

ALTER TABLE bm_release
    ADD COLUMN approved_by VARCHAR(64) NULL COMMENT '审批人（评审员/管理员）',
    ADD COLUMN approved_at DATETIME   NULL COMMENT '审批时间';

ALTER TABLE bm_user
    MODIFY COLUMN role VARCHAR(16) NOT NULL DEFAULT 'VIEWER'
        COMMENT 'ADMIN/EDITOR/REVIEWER/VIEWER';

INSERT INTO bm_user (username, password_hash, display_name, role)
VALUES ('reviewer', '$2b$10$vA11VJJnEP8hHQ1oZ1g7CO1K8BH7w4dwIrPbyqIREhkosH4a3XEs6', '本体评审员', 'REVIEWER');
