-- 来源：A800 算力资源本体评审稿 v0.7。用于规范层展示和评审。
-- DRAFT 表示仅登记定义。调度执行器接入后由评审流程发布。
INSERT INTO bm_rule (rule_code, name, concept_code, rule_type, expression, severity, owner, status, version, expr_json) VALUES
('R-11', '证据新鲜度', 'COMPUTE_EVIDENCE', '校验',
 '观测时间 ≤ 判定时间 ≤ 有效截止时间，且观测年龄不超过最大允许年龄。否则申请排队。',
 '高', '算力平台', 'DRAFT', 1,
 '{"stage":"ADMISSION","inputs":["observedAt","validUntil","asOf","maxAge"],"failureReason":"QUEUE_STALE_EVIDENCE"}'),
('R-04', '目标卡组选择', 'COMPUTE_REQUEST', '推导',
 '单卡优先选择健康且空闲的灵活卡，否则选择 B 组。两卡选择 B 组，三至四卡选择 A 组，五卡及以上排队。',
 '中', '算力平台', 'DRAFT', 1,
 '{"stage":"PLANNING","inputs":["requestedGpuCount","flexGroupState"],"failureReason":"QUEUE_NO_TARGET"}'),
('R-07', '预留与条件写入', 'COMPUTE_ALLOCATION', '约束',
 '预留时校验队首、卡组和共享容量，每组最多一份待生效分配。待生效分配计入预留。',
 '高', '算力平台', 'DRAFT', 1,
 '{"stage":"SUBMISSION","inputs":["groupHolder","pendingAllocation","capacityProjection"],"failureReason":"QUEUE_CONFLICT"}'),
('R-05', '抢占动作与交接', 'COMPUTE_DEPLOYMENT', '约束',
 '先预留，再通知、摘流并等待在途请求结束。停服确认后，在同一事务中关闭原持有区间并激活新分配。',
 '高', '算力平台', 'DRAFT', 1,
 '{"stage":"EXECUTION","inputs":["pendingAllocation","deployment","inFlightRequests","stopReceipt"],"failureReason":"ABORT_INFLIGHT"}'),
('R-15', '原部署恢复可行性', 'COMPUTE_DEPLOYMENT', '校验',
 '恢复前检查整组 GPU 健康和恢复后的资源用量。资源不足时等待资源并重新判定。',
 '高', '算力平台', 'DRAFT', 1,
 '{"stage":"CLOSURE","inputs":["groupHealth","restoreProjection"],"failureReasons":["RECOVERY_UNHEALTHY","RECOVERY_NO_CAPACITY"]}');

INSERT INTO bm_action (action_code, name, concept_code, from_status, to_status, trigger_desc, description, status, version) VALUES
('COMPUTE_DRAIN', '停止接收请求', 'COMPUTE_DEPLOYMENT', '已通知', '已摘流',
 '存在有效待生效分配，抢占通知满足流程条件', '停止接收新请求并等待在途请求结束', 'DRAFT', 1),
('COMPUTE_STOP', '停止常驻模型', 'COMPUTE_DEPLOYMENT', '停服中', '已停服',
 '摘流完成且在途请求结束', '执行停服并核对进程退出及 GPU 释放，确认后允许交接', 'DRAFT', 1),
('COMPUTE_RESTORE', '恢复常驻模型', 'COMPUTE_DEPLOYMENT', '已停服', '恢复中',
 '收尾选择恢复且 R-15 通过', '按已保存的部署配置在原卡组启动，健康检查通过后接回流量', 'DRAFT', 1);

INSERT INTO bm_axiom (axiom_code, subject, predicate, object, axiom_type, description, status) VALUES
('COMPUTE_I05', 'COMPUTE_GPU_GROUP', '约束', '未结束持有区间数 ≤ 1', '约束',
 '同一卡组的持有区间不得重叠。对应评审稿 I-5。', 'DRAFT'),
('COMPUTE_I24', 'COMPUTE_GPU_GROUP', '约束', '待生效分配数 ≤ 1', '约束',
 '每组最多一份待生效分配。待生效及占用状态计入预留。对应评审稿 I-24。', 'DRAFT');
