-- ---------- 链路节点边：节点级追溯关系 ----------
-- 方向约定：沿生命周期流向（先 → 后）。from = 源头/原因侧，to = 结果/闭环侧。
-- rel_type 语义（读作 "from 被/经 to" 或 "from 动作 to"）：
--   CAUSES      变更 → 工单/预警（引发问题）
--   TRIGGERS    工单/预警 → 需求（问题催生需求）
--   LEADS_TO    需求 → 变更（需求落地为变更）
--   COVERED_BY  需求/变更 → 测试用例（被用例覆盖）
--   SHIPPED_BY  变更 → 发布（经发布上线）
--   CLOSED_BY   工单/预警 → 处置单（被处置闭环）
--   RELATES     通用关联
CREATE TABLE IF NOT EXISTS link_rel (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    from_ref_no VARCHAR(64)  NOT NULL COMMENT '上游节点单号（源头/原因侧）',
    to_ref_no   VARCHAR(64)  NOT NULL COMMENT '下游节点单号（结果/闭环侧）',
    rel_type    VARCHAR(32)  NOT NULL COMMENT 'CAUSES/TRIGGERS/LEADS_TO/COVERED_BY/SHIPPED_BY/CLOSED_BY/RELATES',
    remark      VARCHAR(256) COMMENT '关系说明，如来源RCA案例号',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rel (from_ref_no, to_ref_no, rel_type),
    KEY idx_from (from_ref_no),
    KEY idx_to (to_ref_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='链路节点边（节点级追溯）';

-- ---------- 种子边：工业案例「LIS v5.2 状态码变更 → 取消未退费客诉」 ----------
-- 注意 CAUSES 边是跨概念的（LAB_APPLY 的变更引发 FEE_DETAIL 的工单），概念分组无法表达，必须靠边。
INSERT INTO link_rel (from_ref_no, to_ref_no, rel_type, remark) VALUES
('CHG-LIS-20260815-V52', 'T-20260901-001', 'CAUSES', 'LIS撤销状态码X→C，HIS计费适配器未同步，导致张建国血常规取消仍收费'),
('CHG-LIS-20260815-V52', 'T-20260905-002', 'CAUSES', '同一根因：王秀兰肝功能检查取消仍收费'),
('CHG-LIS-20260815-V52', 'DEPLOY-LIS-20260815', 'SHIPPED_BY', 'LIS v5.2生产发布（2026-08-15 02:00-04:00窗口）'),
('REQ-20260610-008', 'TC-FEE-0032', 'COVERED_BY', '取消医嘱不计费校验（缺口：未覆盖LIS侧状态码变更场景）');
