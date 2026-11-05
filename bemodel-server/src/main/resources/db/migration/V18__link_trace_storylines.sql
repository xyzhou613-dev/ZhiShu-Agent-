-- ---------- 链路追溯：模拟真实业务流程的演示数据 ----------
-- 四条故事线覆盖链路的各种健康状态：
--   A. DISPENSE    完整闭环（工单→需求→变更→用例→发布→处置单）—— 健康态
--   B. MEDRECORD_HOME 有变更无用例 —— 质量缺口（warning）
--   C. LAB_REPORT  危急值预警一条已闭环、一条待处置 —— error 态入口
--   D. REFUND      工单无人立项 —— 问题重现未修复（error）
--   E. 既有自动工单回连 LIS 根因变更（与 RCA 全院影响面结论一致）

-- A. 门诊发药状态回写：完整闭环
INSERT INTO link_node (node_type, ref_no, title, concept_code, status, occurred_at, payload) VALUES
('TICKET', 'T-20260802-101', '门诊药房投诉：已发药处方在医生站仍显示"未发药"', 'DISPENSE', '已解决', '2026-08-02 10:15:00',
 '{"proposer":"门诊药房 王药师","content":"患者持已发药处方的医生站界面仍显示未发药，导致重复叫号与患者争执，8月以来已发生6起。","level":"一般投诉"}'),
('REQUIREMENT', 'REQ-20260805-012', '发药状态实时回写医生站', 'DISPENSE', '已上线', '2026-08-05 14:00:00',
 '{"proposer":"门诊部 张主任","description":"药房发药确认后，医生站处方状态须在5秒内刷新为已发药。","accept":"2026-08-22","dev":"HIS门诊组 赵工"}'),
('CHANGE', 'CHG-HIS-20260812-V61', 'HIS v6.1：发药确认接口增加状态回写与失败重试', 'DISPENSE', '已发布', '2026-08-12 20:00:00',
 '{"system":"HIS","version":"v6.1","dev":"HIS门诊组 赵工","detail":"dispense_confirm接口同步回写presc_status，失败进入重试队列（3次，间隔30s）","tables":["dispense_record","prescription"],"risk":"回写延迟高峰期可能达10s，已评估可接受"}'),
('TESTCASE', 'TC-DISP-0018', '发药状态回写一致性回归（含失败重试场景）', 'DISPENSE', '通过', '2026-08-18 11:00:00',
 '{"tester":"测试组 赵敏","coverage":"正常回写/接口超时/重复发药拦截 三场景","last_run":"2026-08-18","result":"通过"}'),
('DEPLOY', 'DEPLOY-HIS-20260820', 'HIS v6.1 生产发布', 'DISPENSE', '成功', '2026-08-20 02:30:00',
 '{"operator":"运维 周舟","window":"02:00-04:00","rollback":"未发生"}'),
('DISPOSAL', 'DIS-20260821-001', '历史处方状态批量核对与投诉人回访', 'DISPENSE', '已完成', '2026-08-21 16:00:00',
 '{"handler":"客服部 小吴","action":"核对8月受影响的214张处方并批量修正状态，逐一回访6起投诉患者","result":"全部闭环，回访满意"}');

INSERT INTO link_rel (from_ref_no, to_ref_no, rel_type, remark) VALUES
('T-20260802-101', 'REQ-20260805-012', 'TRIGGERS', '药房投诉催生发药状态回写需求'),
('REQ-20260805-012', 'CHG-HIS-20260812-V61', 'LEADS_TO', '需求落地为HIS v6.1变更'),
('CHG-HIS-20260812-V61', 'TC-DISP-0018', 'COVERED_BY', '回写一致性回归覆盖'),
('CHG-HIS-20260812-V61', 'DEPLOY-HIS-20260820', 'SHIPPED_BY', '随HIS v6.1上线'),
('T-20260802-101', 'DIS-20260821-001', 'CLOSED_BY', '批量核对+回访后工单闭环');

-- B. 病案首页规则调整：变更已上、测试缺失（质量缺口）
INSERT INTO link_node (node_type, ref_no, title, concept_code, status, occurred_at, payload) VALUES
('REQUIREMENT', 'REQ-20260902-015', '病案首页主要诊断选择规则按医保结算清单2.0调整', 'MEDRECORD_HOME', '进行中', '2026-09-02 09:30:00',
 '{"proposer":"病案室 刘主任","description":"医保结算清单2.0要求主要诊断优先选择消耗医疗资源最多的诊断，质控规则需同步调整。","accept":"2026-09-30","dev":"质控组 孙工"}'),
('CHANGE', 'CHG-QC-20260908-R20', '病案质控规则包v2.0：主要诊断校验规则更新', 'MEDRECORD_HOME', '已发布', '2026-09-08 21:00:00',
 '{"system":"质控平台","version":"规则包v2.0","dev":"质控组 孙工","detail":"RULE-QC-001/002判定逻辑按清单2.0口径重写","risk":"上线前未安排回归测试"}');

INSERT INTO link_rel (from_ref_no, to_ref_no, rel_type, remark) VALUES
('REQ-20260902-015', 'CHG-QC-20260908-R20', 'LEADS_TO', '规则包按需求更新，但无测试用例覆盖');

-- C. 检验危急值：一条已处置闭环
INSERT INTO link_node (node_type, ref_no, title, concept_code, status, occurred_at, payload) VALUES
('ALERT', 'ALERT-LR202609100209', '危急值预警：患者刘伟 血钾6.8mmol/L 检验结果异常未处置', 'LAB_REPORT', '已处置', '2026-09-10 07:55:00',
 '{"patient":"刘伟","inhos_no":"ZY20260820005","dept":"心内科","item":"血钾","result":"6.8mmol/L","report_id":"LR202609100209","report_time":"2026-09-10 07:50"}'),
('DISPOSAL', 'DIS-20260910-002', '危急值处置：电话通知主管医生，补录诊断与处置记录', 'LAB_REPORT', '已完成', '2026-09-10 08:40:00',
 '{"handler":"心内科 值班医生 陈晨","action":"07:58电话通知主管医生，08:20完成补液方案录入，08:40补录诊断（高钾血症）","result":"患者复查血钾5.1mmol/L，预警闭环"}');

INSERT INTO link_rel (from_ref_no, to_ref_no, rel_type, remark) VALUES
('ALERT-LR202609100209', 'DIS-20260910-002', 'CLOSED_BY', '危急值45分钟内处置闭环'),
-- 同一患者杨光：既有取消未退费工单，又有危急值未处置预警（治理视角的关联发现）
('T-AUTO-20260913-006', 'ALERT-LR202608230111', 'RELATES', '同一患者杨光（ZY20260822007）：既有未退费工单又有危急值未处置');

-- D. 退费工单：无人立项（问题重现未修复）
INSERT INTO link_node (node_type, ref_no, title, concept_code, status, occurred_at, payload) VALUES
('TICKET', 'T-20260911-101', '患者投诉：出院退费3个工作日未到账', 'REFUND', '待处理', '2026-09-11 13:20:00',
 '{"patient":"赵桂芳","inhos_no":"ZY20260902011","content":"9月6日出院结算多退的320元，至今未退回银行卡。","agent":"客服 小吴","level":"一般投诉"}'),
('TICKET', 'T-20260912-102', '患者投诉：门诊退费申请提交后无任何进度', 'REFUND', '待处理', '2026-09-12 10:05:00',
 '{"patient":"孙磊","phone":"139****3302","content":"9月9日App提交门诊退费申请，3天过去状态仍是审核中。","agent":"客服 小吴","level":"一般投诉"}');

-- E. 既有自动工单回连 LIS 根因变更（RCA 已证明全院「取消未退费」同一根因）
INSERT INTO link_rel (from_ref_no, to_ref_no, rel_type, remark) VALUES
('CHG-LIS-20260815-V52', 'T-AUTO-20260913-004', 'CAUSES', '同一根因（LIS状态码裂缝）：王强 取消未退费80元'),
('CHG-LIS-20260815-V52', 'T-AUTO-20260913-005', 'CAUSES', '同一根因（LIS状态码裂缝）：刘伟 取消未退费15元'),
('CHG-LIS-20260815-V52', 'T-AUTO-20260913-006', 'CAUSES', '同一根因（LIS状态码裂缝）：杨光 取消未退费25元'),
('CHG-LIS-20260815-V52', 'T-GOV-GOV-004', 'CAUSES', '同一根因（LIS状态码裂缝）：张建国（治理模块工单）');
