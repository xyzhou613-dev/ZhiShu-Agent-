-- =============================================================
-- V3: 治理种子数据 —— 本体 / 数据源注册 / 映射 / 链路节点
-- （产品库业务演示数据由应用启动时 DataSeeder 生成）
-- =============================================================

-- ---------- 业务域 ----------
INSERT INTO bm_domain (code, name, description, sort) VALUES
('PATIENT_DOMAIN', '患者域', '患者主索引与就诊过程', 1),
('CLINICAL', '临床域', '医嘱、诊疗行为', 2),
('MEDTECH', '医技域', '检验、检查等医技业务', 3),
('FEE', '费用域', '费用、结算、退费', 4),
('OPS', '运营域', '运营指标与监管', 5);

-- ---------- 业务概念（7个核心概念，支撑根因案例） ----------
INSERT INTO bm_concept (code, name, domain_code, definition, owner, status, version) VALUES
('PATIENT', '患者', 'PATIENT_DOMAIN', '接受医疗服务的自然人。各产品叫法不一：HIS称病员、LIS称受检者，统一口径为患者。', '王架构', 'PUBLISHED', 3),
('INP_VISIT', '住院就诊', 'PATIENT_DOMAIN', '患者一次住院过程，以住院号为标识，从入院到出院。', '王架构', 'PUBLISHED', 2),
('MEDICAL_ORDER', '医嘱', 'CLINICAL', '医生下达的诊疗指令，含检验、检查、药品等类型。状态口径：未执行/已执行/已取消。', '李临床', 'PUBLISHED', 2),
('LAB_APPLY', '检验申请', 'MEDTECH', '检验医嘱落地到LIS形成的申请单，状态口径：已采样/已发布/已撤销。', '张医技', 'PUBLISHED', 2),
('LAB_REPORT', '检验报告', 'MEDTECH', '检验申请执行后产出的报告。', '张医技', 'PUBLISHED', 1),
('FEE_DETAIL', '费用明细', 'FEE', '患者单次计费的最小粒度记录，通常由医嘱触发。状态口径：正常/已退费。', '陈财务', 'PUBLISHED', 4),
('SETTLEMENT', '结算记录', 'FEE', '患者出院时对整个住院费用的汇总结算。', '陈财务', 'PUBLISHED', 1);

-- ---------- 概念属性 ----------
INSERT INTO bm_attribute (concept_code, attr_code, attr_name, data_type, is_key, definition, sort) VALUES
('PATIENT', 'name', '姓名', 'STRING', 0, '患者姓名', 1),
('PATIENT', 'sex', '性别', 'ENUM', 0, '男/女', 2),
('PATIENT', 'age', '年龄', 'NUMBER', 0, '周岁', 3),
('INP_VISIT', 'visit_no', '住院号', 'STRING', 1, '住院就诊唯一标识。注意：LIS的patient_no字段实际存的也是住院号', 1),
('INP_VISIT', 'ward', '病区', 'STRING', 0, NULL, 2),
('INP_VISIT', 'dept', '科室', 'STRING', 0, NULL, 3),
('INP_VISIT', 'admit_time', '入院时间', 'DATE', 0, NULL, 4),
('INP_VISIT', 'discharge_time', '出院时间', 'DATE', 0, NULL, 5),
('INP_VISIT', 'status', '就诊状态', 'ENUM', 0, '在院/出院', 6),
('MEDICAL_ORDER', 'order_id', '医嘱号', 'STRING', 1, '医嘱唯一标识', 1),
('MEDICAL_ORDER', 'item_code', '项目编码', 'STRING', 0, '收费/检验项目编码', 2),
('MEDICAL_ORDER', 'item_name', '项目名称', 'STRING', 0, NULL, 3),
('MEDICAL_ORDER', 'status', '医嘱状态', 'ENUM', 0, '标准口径：未执行/已执行/已取消', 4),
('MEDICAL_ORDER', 'create_time', '开立时间', 'DATE', 0, NULL, 5),
('LAB_APPLY', 'apply_id', '申请号', 'STRING', 1, '检验申请唯一标识', 1),
('LAB_APPLY', 'item_code', '项目编码', 'STRING', 0, NULL, 2),
('LAB_APPLY', 'status', '申请状态', 'ENUM', 0, '标准口径：已采样/已发布/已撤销', 3),
('LAB_APPLY', 'apply_time', '申请时间', 'DATE', 0, NULL, 4),
('LAB_REPORT', 'report_id', '报告号', 'STRING', 1, NULL, 1),
('LAB_REPORT', 'status', '结果状态', 'ENUM', 0, '正常/异常', 2),
('LAB_REPORT', 'report_time', '报告时间', 'DATE', 0, NULL, 3),
('FEE_DETAIL', 'fee_id', '费用流水号', 'STRING', 1, NULL, 1),
('FEE_DETAIL', 'item_code', '项目编码', 'STRING', 0, NULL, 2),
('FEE_DETAIL', 'amount', '金额', 'NUMBER', 0, '单位：元', 3),
('FEE_DETAIL', 'status', '费用状态', 'ENUM', 0, '标准口径：正常/已退费', 4),
('FEE_DETAIL', 'charge_time', '计费时间', 'DATE', 0, NULL, 5),
('SETTLEMENT', 'settle_id', '结算号', 'STRING', 1, NULL, 1),
('SETTLEMENT', 'total_amount', '结算总额', 'NUMBER', 0, NULL, 2),
('SETTLEMENT', 'settle_time', '结算时间', 'DATE', 0, NULL, 3);

-- ---------- 概念关系（本体图的边） ----------
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description) VALUES
('PATIENT', 'INP_VISIT', '发生就诊', '一个患者可有多次住院就诊'),
('INP_VISIT', 'MEDICAL_ORDER', '下达', '一次就诊下产生多条医嘱'),
('MEDICAL_ORDER', 'LAB_APPLY', '生成申请', '检验类医嘱在LIS生成申请单'),
('LAB_APPLY', 'LAB_REPORT', '生成报告', '申请执行后产出报告'),
('MEDICAL_ORDER', 'FEE_DETAIL', '产生费用', '医嘱触发计费'),
('INP_VISIT', 'SETTLEMENT', '出院结算', '就诊结束时汇总结算'),
('FEE_DETAIL', 'SETTLEMENT', '汇总入', '费用明细汇总进入结算');

-- ---------- 术语库（治"叫法不统一"） ----------
INSERT INTO bm_term (term, concept_code, source_product, term_type) VALUES
('患者', 'PATIENT', '平台标准', 'STANDARD'),
('病员', 'PATIENT', 'HIS', 'ALIAS'),
('受检者', 'PATIENT', 'LIS', 'ALIAS'),
('病人', 'PATIENT', '门诊', 'ALIAS'),
('住院号', 'INP_VISIT', '平台标准', 'STANDARD'),
('病员号', 'INP_VISIT', 'HIS', 'ALIAS'),
('患者编号', 'INP_VISIT', 'LIS', 'ALIAS'),
('医嘱', 'MEDICAL_ORDER', '平台标准', 'STANDARD'),
('化验单', 'LAB_APPLY', 'LIS', 'ALIAS'),
('检验申请', 'LAB_APPLY', '平台标准', 'STANDARD'),
('费用流水', 'FEE_DETAIL', '收费系统', 'ALIAS');

-- ---------- 指标口径库 ----------
INSERT INTO bm_metric (metric_code, name, definition, formula, concept_code, owner) VALUES
('DISCHARGE_COUNT', '出院人数', '统计周期内完成出院结算的住院就诊人次，按结算时间归属统计周期；转科不重复计。', 'COUNT(DISTINCT 住院号) WHERE 结算状态=已结算 AND 结算时间 IN 周期', 'SETTLEMENT', '陈财务'),
('LAB_CANCEL_RATE', '检验撤销率', '检验申请中已撤销的占比，反映开单质量。', 'COUNT(申请状态=已撤销) / COUNT(全部申请)', 'LAB_APPLY', '张医技'),
('AVG_INP_FEE', '出院患者均次费用', '出院结算总额 / 出院人数。', 'SUM(结算总额) / COUNT(DISTINCT 住院号)', 'SETTLEMENT', '陈财务'),
('CANCEL_NOT_REFUND', '取消未退费笔数', '医嘱已取消但费用状态仍为正常的明细笔数，计费一致性核心监控指标，正常应为0。', 'COUNT(医嘱状态=已取消 AND 费用状态=正常)', 'FEE_DETAIL', '陈财务');

-- ---------- 数据源注册 ----------
INSERT INTO bm_datasource (ds_code, ds_name, product_name, db_type, host, port, db_name, username, password) VALUES
('DS_HIS', '住院HIS库', '住院HIS', 'MYSQL', '127.0.0.1', 3306, 'demo_his', '${demo_db_username}', '${demo_db_password}'),
('DS_LIS', '检验LIS库', 'LIS检验', 'MYSQL', '127.0.0.1', 3306, 'demo_lis', '${demo_db_username}', '${demo_db_password}'),
('DS_CHARGE', '收费结算库', '收费系统', 'MYSQL', '127.0.0.1', 3306, 'demo_charge', '${demo_db_username}', '${demo_db_password}');

-- ---------- 物理列 ↔ 概念属性 映射（含值字典映射） ----------
-- demo_his.inpatient
INSERT INTO bm_mapping (ds_code, table_name, column_name, concept_code, attr_code, value_map, confirmed, source) VALUES
('DS_HIS', 'inpatient', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_HIS', 'inpatient', 'patient_name', 'PATIENT', 'name', NULL, 1, 'MANUAL'),
('DS_HIS', 'inpatient', 'sex', 'PATIENT', 'sex', NULL, 1, 'MANUAL'),
('DS_HIS', 'inpatient', 'age', 'PATIENT', 'age', NULL, 1, 'MANUAL'),
('DS_HIS', 'inpatient', 'ward', 'INP_VISIT', 'ward', NULL, 1, 'MANUAL'),
('DS_HIS', 'inpatient', 'dept', 'INP_VISIT', 'dept', NULL, 1, 'MANUAL'),
('DS_HIS', 'inpatient', 'admit_time', 'INP_VISIT', 'admit_time', NULL, 1, 'MANUAL'),
('DS_HIS', 'inpatient', 'discharge_time', 'INP_VISIT', 'discharge_time', NULL, 1, 'MANUAL'),
('DS_HIS', 'inpatient', 'status', 'INP_VISIT', 'status', NULL, 1, 'MANUAL'),
-- demo_his.medical_order
('DS_HIS', 'medical_order', 'order_id', 'MEDICAL_ORDER', 'order_id', NULL, 1, 'MANUAL'),
('DS_HIS', 'medical_order', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_HIS', 'medical_order', 'item_code', 'MEDICAL_ORDER', 'item_code', NULL, 1, 'MANUAL'),
('DS_HIS', 'medical_order', 'item_name', 'MEDICAL_ORDER', 'item_name', NULL, 1, 'MANUAL'),
('DS_HIS', 'medical_order', 'order_status', 'MEDICAL_ORDER', 'status', '{"0":"未执行","1":"已执行","2":"已取消"}', 1, 'MANUAL'),
('DS_HIS', 'medical_order', 'create_time', 'MEDICAL_ORDER', 'create_time', NULL, 1, 'MANUAL'),
-- demo_his.fee_detail
('DS_HIS', 'fee_detail', 'fee_id', 'FEE_DETAIL', 'fee_id', NULL, 1, 'MANUAL'),
('DS_HIS', 'fee_detail', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_HIS', 'fee_detail', 'order_id', 'MEDICAL_ORDER', 'order_id', NULL, 1, 'MANUAL'),
('DS_HIS', 'fee_detail', 'item_code', 'FEE_DETAIL', 'item_code', NULL, 1, 'MANUAL'),
('DS_HIS', 'fee_detail', 'item_name', 'MEDICAL_ORDER', 'item_name', NULL, 1, 'MANUAL'),
('DS_HIS', 'fee_detail', 'amount', 'FEE_DETAIL', 'amount', NULL, 1, 'MANUAL'),
('DS_HIS', 'fee_detail', 'fee_status', 'FEE_DETAIL', 'status', '{"1":"正常","2":"已退费"}', 1, 'MANUAL'),
('DS_HIS', 'fee_detail', 'charge_time', 'FEE_DETAIL', 'charge_time', NULL, 1, 'MANUAL'),
-- demo_lis.lab_apply
('DS_LIS', 'lab_apply', 'apply_id', 'LAB_APPLY', 'apply_id', NULL, 1, 'MANUAL'),
('DS_LIS', 'lab_apply', 'order_id', 'MEDICAL_ORDER', 'order_id', NULL, 1, 'MANUAL'),
('DS_LIS', 'lab_apply', 'patient_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_LIS', 'lab_apply', 'patient_name', 'PATIENT', 'name', NULL, 1, 'MANUAL'),
('DS_LIS', 'lab_apply', 'item_code', 'LAB_APPLY', 'item_code', NULL, 1, 'MANUAL'),
('DS_LIS', 'lab_apply', 'item_name', 'LAB_APPLY', 'item_code', NULL, 1, 'MANUAL'),
('DS_LIS', 'lab_apply', 'apply_status', 'LAB_APPLY', 'status', '{"N":"已采样","P":"已发布","C":"已撤销","X":"已撤销(v5.1旧码)"}', 1, 'MANUAL'),
('DS_LIS', 'lab_apply', 'apply_time', 'LAB_APPLY', 'apply_time', NULL, 1, 'MANUAL'),
-- demo_lis.lab_report
('DS_LIS', 'lab_report', 'report_id', 'LAB_REPORT', 'report_id', NULL, 1, 'MANUAL'),
('DS_LIS', 'lab_report', 'apply_id', 'LAB_APPLY', 'apply_id', NULL, 1, 'MANUAL'),
('DS_LIS', 'lab_report', 'patient_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_LIS', 'lab_report', 'result_status', 'LAB_REPORT', 'status', '{"N":"正常","A":"异常"}', 1, 'MANUAL'),
('DS_LIS', 'lab_report', 'report_time', 'LAB_REPORT', 'report_time', NULL, 1, 'MANUAL'),
-- demo_charge.settlement
('DS_CHARGE', 'settlement', 'settle_id', 'SETTLEMENT', 'settle_id', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'settlement', 'inhos_no', 'INP_VISIT', 'visit_no', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'settlement', 'patient_name', 'PATIENT', 'name', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'settlement', 'total_amount', 'SETTLEMENT', 'total_amount', NULL, 1, 'MANUAL'),
('DS_CHARGE', 'settlement', 'settle_time', 'SETTLEMENT', 'settle_time', NULL, 1, 'MANUAL');

-- ---------- 链路节点种子（需求-研发-测试-运维-客服） ----------
INSERT INTO link_node (node_type, ref_no, title, concept_code, status, occurred_at, payload) VALUES
('REQUIREMENT', 'REQ-20260610-008', '检验撤销后自动退费', 'FEE_DETAIL', '已上线', '2026-06-10 10:00:00',
 '{"proposer":"财务部 陈静","description":"检验申请撤销后，适配器应自动触发对应费用退费，避免患者多缴。","accept":"2026-06-28","dev":"HIS计费组 刘工"}'),
('TESTCASE', 'TC-FEE-0032', '取消医嘱不计费校验', 'FEE_DETAIL', '通过', '2026-08-10 14:00:00',
 '{"tester":"测试组 赵敏","coverage":"仅覆盖HIS医生站取消医嘱(order_status=2)路径","last_run":"2026-08-10","result":"通过","gap":"未覆盖LIS侧撤销状态码变更场景"}'),
('CHANGE', 'CHG-LIS-20260815-V52', 'LIS v5.2升级：申请撤销状态码由X改为C', 'LAB_APPLY', '已发布', '2026-08-15 02:00:00',
 '{"system":"LIS","version":"v5.2","dev":"LIS组 孙工","detail":"申请撤销状态码X废弃，统一为C；lab_dict_status新增C、X标记下线","tables":["lab_apply","lab_dict_status"],"risk":"通知下游HIS计费适配器同步映射（口头通知，无工单跟踪）"}'),
('DEPLOY', 'DEPLOY-LIS-20260815', 'LIS v5.2生产发布', 'LAB_APPLY', '成功', '2026-08-15 03:30:00',
 '{"operator":"运维 周舟","window":"02:00-04:00","rollback":"未发生"}'),
('TICKET', 'T-20260901-001', '患者张建国投诉：血常规重复收费', 'FEE_DETAIL', '处理中', '2026-09-01 09:23:00',
 '{"patient":"张建国","inhos_no":"ZY20260815001","phone":"138****6621","content":"8月21日医生告知复查的血常规已取消不做，出院结算仍收取25元。","agent":"客服 小吴","level":"一般投诉"}'),
('TICKET', 'T-20260905-002', '患者王秀兰投诉：取消的肝功能检查仍收费', 'FEE_DETAIL', '处理中', '2026-09-05 15:41:00',
 '{"patient":"王秀兰","inhos_no":"ZY20260818004","content":"取消的肝功能项目仍被收费60元。","agent":"客服 小吴","level":"一般投诉"}');
