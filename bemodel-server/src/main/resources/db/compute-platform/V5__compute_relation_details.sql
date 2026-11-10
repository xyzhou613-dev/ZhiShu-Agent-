-- M-01：按 v0.7 对齐 30 条类型关系；基数是建模说明，不代替业务约束。
-- 仅原位调整仍与 V2 初始化说明完全一致的旧关系；自建或已修改的关系保留。

UPDATE bm_relation legacy LEFT JOIN bm_relation canonical ON canonical.from_concept = 'COMPUTE_EMPLOYEE' AND canonical.to_concept = 'COMPUTE_REQUEST' AND canonical.relation_name = '提出'
SET legacy.from_concept = 'COMPUTE_EMPLOYEE', legacy.to_concept = 'COMPUTE_REQUEST', legacy.relation_name = '提出', legacy.description = '基数 1 : 0..N；已有字段：compute_request.employee_id；员工提交申请。'
WHERE legacy.from_concept = 'COMPUTE_EMPLOYEE' AND legacy.to_concept = 'COMPUTE_REQUEST' AND legacy.relation_name = '发起' AND legacy.description = '算力域对象关系' AND canonical.id IS NULL;
UPDATE bm_relation legacy LEFT JOIN bm_relation canonical ON canonical.from_concept = 'COMPUTE_PROJECT' AND canonical.to_concept = 'COMPUTE_REQUEST' AND canonical.relation_name = '归属'
SET legacy.from_concept = 'COMPUTE_PROJECT', legacy.to_concept = 'COMPUTE_REQUEST', legacy.relation_name = '归属', legacy.description = '基数 1 : 0..N；已有字段：compute_request.project_id；方向按 v0.7 为项目→申请，不增加反向重复标准边。'
WHERE legacy.from_concept = 'COMPUTE_REQUEST' AND legacy.to_concept = 'COMPUTE_PROJECT' AND legacy.relation_name = '归属' AND legacy.description = '算力域对象关系' AND canonical.id IS NULL;
UPDATE bm_relation legacy LEFT JOIN bm_relation canonical ON canonical.from_concept = 'COMPUTE_REQUEST' AND canonical.to_concept = 'COMPUTE_TASK_TYPE' AND canonical.relation_name = '指定（期望）'
SET legacy.from_concept = 'COMPUTE_REQUEST', legacy.to_concept = 'COMPUTE_TASK_TYPE', legacy.relation_name = '指定（期望）', legacy.description = '基数 N : 1；已有字段：compute_request.task_type_id；与作业实际类型分开。'
WHERE legacy.from_concept = 'COMPUTE_REQUEST' AND legacy.to_concept = 'COMPUTE_TASK_TYPE' AND legacy.relation_name = '采用' AND legacy.description = '算力域对象关系' AND canonical.id IS NULL;
UPDATE bm_relation legacy LEFT JOIN bm_relation canonical ON canonical.from_concept = 'COMPUTE_HOLD_PERIOD' AND canonical.to_concept = 'COMPUTE_GPU_GROUP' AND canonical.relation_name = '持有'
SET legacy.from_concept = 'COMPUTE_HOLD_PERIOD', legacy.to_concept = 'COMPUTE_GPU_GROUP', legacy.relation_name = '持有', legacy.description = '基数 N : 1；已有字段：hold_period.group_id；同组未结束持有最多一个，待生效分配不持有（I-5/I-21）。'
WHERE legacy.from_concept = 'COMPUTE_HOLD_PERIOD' AND legacy.to_concept = 'COMPUTE_GPU_GROUP' AND legacy.relation_name = '记录' AND legacy.description = '算力域对象关系' AND canonical.id IS NULL;
UPDATE bm_relation legacy LEFT JOIN bm_relation canonical ON canonical.from_concept = 'COMPUTE_HOLD_PERIOD' AND canonical.to_concept = 'COMPUTE_DEPLOYMENT' AND canonical.relation_name = '持有主体'
SET legacy.from_concept = 'COMPUTE_HOLD_PERIOD', legacy.to_concept = 'COMPUTE_DEPLOYMENT', legacy.relation_name = '持有主体', legacy.description = '基数 N : 0..1；已有字段：hold_period.deployment_id；与分配主体互斥且恰好一个，部署持有绑定配置版本。'
WHERE legacy.from_concept = 'COMPUTE_HOLD_PERIOD' AND legacy.to_concept = 'COMPUTE_DEPLOYMENT' AND legacy.relation_name = '部署持有者' AND legacy.description = '算力域对象关系' AND canonical.id IS NULL;
UPDATE bm_relation legacy LEFT JOIN bm_relation canonical ON canonical.from_concept = 'COMPUTE_HOLD_PERIOD' AND canonical.to_concept = 'COMPUTE_ALLOCATION' AND canonical.relation_name = '持有主体'
SET legacy.from_concept = 'COMPUTE_HOLD_PERIOD', legacy.to_concept = 'COMPUTE_ALLOCATION', legacy.relation_name = '持有主体', legacy.description = '基数 N : 0..1；已有字段：hold_period.allocation_id；与部署主体互斥且恰好一个，只能是占用分配。'
WHERE legacy.from_concept = 'COMPUTE_HOLD_PERIOD' AND legacy.to_concept = 'COMPUTE_ALLOCATION' AND legacy.relation_name = '分配持有者' AND legacy.description = '算力域对象关系' AND canonical.id IS NULL;
UPDATE bm_relation legacy LEFT JOIN bm_relation canonical ON canonical.from_concept = 'COMPUTE_REQUEST' AND canonical.to_concept = 'COMPUTE_DECISION' AND canonical.relation_name = '触发'
SET legacy.from_concept = 'COMPUTE_REQUEST', legacy.to_concept = 'COMPUTE_DECISION', legacy.relation_name = '触发', legacy.description = '基数 1 : 0..N；已有字段：decision.request_id；一申请可有多次、多个类型的判断。'
WHERE legacy.from_concept = 'COMPUTE_REQUEST' AND legacy.to_concept = 'COMPUTE_DECISION' AND legacy.relation_name = '产生' AND legacy.description = '算力域对象关系' AND canonical.id IS NULL;
UPDATE bm_relation legacy LEFT JOIN bm_relation canonical ON canonical.from_concept = 'COMPUTE_DECISION' AND canonical.to_concept = 'COMPUTE_ACTION' AND canonical.relation_name = '产生'
SET legacy.from_concept = 'COMPUTE_DECISION', legacy.to_concept = 'COMPUTE_ACTION', legacy.relation_name = '产生', legacy.description = '基数 1 : 0..N；已有字段：execution_action.decision_id；批准不等于动作成功。'
WHERE legacy.from_concept = 'COMPUTE_DECISION' AND legacy.to_concept = 'COMPUTE_ACTION' AND legacy.relation_name = '触发' AND legacy.description = '算力域对象关系' AND canonical.id IS NULL;

-- 已存在的标准关系保留用户修改；原始种子说明才补齐基数和来源。
UPDATE bm_relation SET description = '基数 1 : 1..N；已有字段：gpu_group.node_id；节点包含卡组。' WHERE from_concept = 'COMPUTE_NODE' AND to_concept = 'COMPUTE_GPU_GROUP' AND relation_name = '包含' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_NODE', 'COMPUTE_GPU_GROUP', '包含', '基数 1 : 1..N；已有字段：gpu_group.node_id；节点包含卡组。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_NODE' AND to_concept = 'COMPUTE_GPU_GROUP' AND relation_name = '包含');
UPDATE bm_relation SET description = '基数 1 : 1..N；已有字段：gpu_device.group_id；GPU 归属一个卡组。' WHERE from_concept = 'COMPUTE_GPU_GROUP' AND to_concept = 'COMPUTE_GPU_DEVICE' AND relation_name = '包含' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_GPU_GROUP', 'COMPUTE_GPU_DEVICE', '包含', '基数 1 : 1..N；已有字段：gpu_device.group_id；GPU 归属一个卡组。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_GPU_GROUP' AND to_concept = 'COMPUTE_GPU_DEVICE' AND relation_name = '包含');
UPDATE bm_relation SET description = '基数 1 : 0..N；已有字段：deployment.group_id；类型保留历史基数，首期每组最多一个未退役部署（I-4/I-14）。' WHERE from_concept = 'COMPUTE_GPU_GROUP' AND to_concept = 'COMPUTE_DEPLOYMENT' AND relation_name = '承载' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_GPU_GROUP', 'COMPUTE_DEPLOYMENT', '承载', '基数 1 : 0..N；已有字段：deployment.group_id；类型保留历史基数，首期每组最多一个未退役部署（I-4/I-14）。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_GPU_GROUP' AND to_concept = 'COMPUTE_DEPLOYMENT' AND relation_name = '承载');
UPDATE bm_relation SET description = '基数 N : 1；已有字段：deployment.project_id；用于通知与成本归属。' WHERE from_concept = 'COMPUTE_DEPLOYMENT' AND to_concept = 'COMPUTE_PROJECT' AND relation_name = '归属' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_DEPLOYMENT', 'COMPUTE_PROJECT', '归属', '基数 N : 1；已有字段：deployment.project_id；用于通知与成本归属。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_DEPLOYMENT' AND to_concept = 'COMPUTE_PROJECT' AND relation_name = '归属');
UPDATE bm_relation SET description = '基数 N : 1；尚未落库：当前仅 deployment.owner_employee_id 记录个人负责人，不能据此假定其团队即责任团队。' WHERE from_concept = 'COMPUTE_DEPLOYMENT' AND to_concept = 'COMPUTE_TEAM' AND relation_name = '负责' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_DEPLOYMENT', 'COMPUTE_TEAM', '负责', '基数 N : 1；尚未落库：当前仅 deployment.owner_employee_id 记录个人负责人，不能据此假定其团队即责任团队。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_DEPLOYMENT' AND to_concept = 'COMPUTE_TEAM' AND relation_name = '负责');
UPDATE bm_relation SET description = '基数 N : 1；已有字段：employee.team_id；所属团队，不是部门权限。' WHERE from_concept = 'COMPUTE_EMPLOYEE' AND to_concept = 'COMPUTE_TEAM' AND relation_name = '属于' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_EMPLOYEE', 'COMPUTE_TEAM', '属于', '基数 N : 1；已有字段：employee.team_id；所属团队，不是部门权限。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_EMPLOYEE' AND to_concept = 'COMPUTE_TEAM' AND relation_name = '属于');
UPDATE bm_relation SET description = '基数 N : N；关联查询：project_team；项目与团队多对多。' WHERE from_concept = 'COMPUTE_TEAM' AND to_concept = 'COMPUTE_PROJECT' AND relation_name = '参与' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_TEAM', 'COMPUTE_PROJECT', '参与', '基数 N : N；关联查询：project_team；项目与团队多对多。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_TEAM' AND to_concept = 'COMPUTE_PROJECT' AND relation_name = '参与');
UPDATE bm_relation SET description = '基数 1 : 0..N；已有字段：compute_request.employee_id；员工提交申请。' WHERE from_concept = 'COMPUTE_EMPLOYEE' AND to_concept = 'COMPUTE_REQUEST' AND relation_name = '提出' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_EMPLOYEE', 'COMPUTE_REQUEST', '提出', '基数 1 : 0..N；已有字段：compute_request.employee_id；员工提交申请。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_EMPLOYEE' AND to_concept = 'COMPUTE_REQUEST' AND relation_name = '提出');
UPDATE bm_relation SET description = '基数 1 : 0..N；已有字段：compute_request.project_id；方向按 v0.7 为项目→申请，不增加反向重复标准边。' WHERE from_concept = 'COMPUTE_PROJECT' AND to_concept = 'COMPUTE_REQUEST' AND relation_name = '归属' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_PROJECT', 'COMPUTE_REQUEST', '归属', '基数 1 : 0..N；已有字段：compute_request.project_id；方向按 v0.7 为项目→申请，不增加反向重复标准边。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_PROJECT' AND to_concept = 'COMPUTE_REQUEST' AND relation_name = '归属');
UPDATE bm_relation SET description = '基数 N : 1；已有字段：compute_request.task_type_id；与作业实际类型分开。' WHERE from_concept = 'COMPUTE_REQUEST' AND to_concept = 'COMPUTE_TASK_TYPE' AND relation_name = '指定（期望）' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_REQUEST', 'COMPUTE_TASK_TYPE', '指定（期望）', '基数 N : 1；已有字段：compute_request.task_type_id；与作业实际类型分开。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_REQUEST' AND to_concept = 'COMPUTE_TASK_TYPE' AND relation_name = '指定（期望）');
UPDATE bm_relation SET description = '基数 1 : 0..N；已有字段：compute_job.request_id；历史多次尝试，同时最多一个活跃作业（I-1）。' WHERE from_concept = 'COMPUTE_REQUEST' AND to_concept = 'COMPUTE_JOB' AND relation_name = '产生' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_REQUEST', 'COMPUTE_JOB', '产生', '基数 1 : 0..N；已有字段：compute_job.request_id；历史多次尝试，同时最多一个活跃作业（I-1）。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_REQUEST' AND to_concept = 'COMPUTE_JOB' AND relation_name = '产生');
UPDATE bm_relation SET description = '基数 N : 1；已有字段：compute_job.task_type_id；不一致需改派决策（I-8），当前尚无改派执行能力。' WHERE from_concept = 'COMPUTE_JOB' AND to_concept = 'COMPUTE_TASK_TYPE' AND relation_name = '实例化（实际）' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_JOB', 'COMPUTE_TASK_TYPE', '实例化（实际）', '基数 N : 1；已有字段：compute_job.task_type_id；不一致需改派决策（I-8），当前尚无改派执行能力。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_JOB' AND to_concept = 'COMPUTE_TASK_TYPE' AND relation_name = '实例化（实际）');
UPDATE bm_relation SET description = '基数 1 : 0..1；已有字段：gpu_allocation.job_id；一次作业最多一份分配（I-2），关系名不意味着分配已经生效。' WHERE from_concept = 'COMPUTE_JOB' AND to_concept = 'COMPUTE_ALLOCATION' AND relation_name = '占用' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_JOB', 'COMPUTE_ALLOCATION', '占用', '基数 1 : 0..1；已有字段：gpu_allocation.job_id；一次作业最多一份分配（I-2），关系名不意味着分配已经生效。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_JOB' AND to_concept = 'COMPUTE_ALLOCATION' AND relation_name = '占用');
UPDATE bm_relation SET description = '基数 N : 1；已有字段：gpu_allocation.group_id；按整组预留和释放。' WHERE from_concept = 'COMPUTE_ALLOCATION' AND to_concept = 'COMPUTE_GPU_GROUP' AND relation_name = '绑定' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_ALLOCATION', 'COMPUTE_GPU_GROUP', '绑定', '基数 N : 1；已有字段：gpu_allocation.group_id；按整组预留和释放。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_ALLOCATION' AND to_concept = 'COMPUTE_GPU_GROUP' AND relation_name = '绑定');
UPDATE bm_relation SET description = '基数 N : 0..N；关联查询：allocation_gpu；待生效可为零，生效后数量等于作业实际用卡数（I-11）。' WHERE from_concept = 'COMPUTE_ALLOCATION' AND to_concept = 'COMPUTE_GPU_DEVICE' AND relation_name = '使用' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_ALLOCATION', 'COMPUTE_GPU_DEVICE', '使用', '基数 N : 0..N；关联查询：allocation_gpu；待生效可为零，生效后数量等于作业实际用卡数（I-11）。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_ALLOCATION' AND to_concept = 'COMPUTE_GPU_DEVICE' AND relation_name = '使用');
UPDATE bm_relation SET description = '基数 N : 1；已有字段：hold_period.group_id；同组未结束持有最多一个，待生效分配不持有（I-5/I-21）。' WHERE from_concept = 'COMPUTE_HOLD_PERIOD' AND to_concept = 'COMPUTE_GPU_GROUP' AND relation_name = '持有' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_HOLD_PERIOD', 'COMPUTE_GPU_GROUP', '持有', '基数 N : 1；已有字段：hold_period.group_id；同组未结束持有最多一个，待生效分配不持有（I-5/I-21）。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_HOLD_PERIOD' AND to_concept = 'COMPUTE_GPU_GROUP' AND relation_name = '持有');
UPDATE bm_relation SET description = '基数 N : 0..1；已有字段：hold_period.deployment_id；与分配主体互斥且恰好一个，部署持有绑定配置版本。' WHERE from_concept = 'COMPUTE_HOLD_PERIOD' AND to_concept = 'COMPUTE_DEPLOYMENT' AND relation_name = '持有主体' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_HOLD_PERIOD', 'COMPUTE_DEPLOYMENT', '持有主体', '基数 N : 0..1；已有字段：hold_period.deployment_id；与分配主体互斥且恰好一个，部署持有绑定配置版本。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_HOLD_PERIOD' AND to_concept = 'COMPUTE_DEPLOYMENT' AND relation_name = '持有主体');
UPDATE bm_relation SET description = '基数 N : 0..1；已有字段：hold_period.allocation_id；与部署主体互斥且恰好一个，只能是占用分配。' WHERE from_concept = 'COMPUTE_HOLD_PERIOD' AND to_concept = 'COMPUTE_ALLOCATION' AND relation_name = '持有主体' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_HOLD_PERIOD', 'COMPUTE_ALLOCATION', '持有主体', '基数 N : 0..1；已有字段：hold_period.allocation_id；与部署主体互斥且恰好一个，只能是占用分配。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_HOLD_PERIOD' AND to_concept = 'COMPUTE_ALLOCATION' AND relation_name = '持有主体');
UPDATE bm_relation SET description = '基数 1 : 0..N；已有字段：decision.request_id；一申请可有多次、多个类型的判断。' WHERE from_concept = 'COMPUTE_REQUEST' AND to_concept = 'COMPUTE_DECISION' AND relation_name = '触发' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_REQUEST', 'COMPUTE_DECISION', '触发', '基数 1 : 0..N；已有字段：decision.request_id；一申请可有多次、多个类型的判断。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_REQUEST' AND to_concept = 'COMPUTE_DECISION' AND relation_name = '触发');
UPDATE bm_relation SET description = '基数 0..1 : 0..N；已有字段：decision.previous_decision_id；按前序决策→后续决策反查，单条后续决策至多一个前序，不等于替代。' WHERE from_concept = 'COMPUTE_DECISION' AND to_concept = 'COMPUTE_DECISION' AND relation_name = '前序' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_DECISION', 'COMPUTE_DECISION', '前序', '基数 0..1 : 0..N；已有字段：decision.previous_decision_id；按前序决策→后续决策反查，单条后续决策至多一个前序，不等于替代。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_DECISION' AND to_concept = 'COMPUTE_DECISION' AND relation_name = '前序');
UPDATE bm_relation SET description = '基数 0..1 : 0..N；尚未落库：当前 decision 没有触发事件外键；resource_event.causing_decision_id 表示反方向的事件来源。' WHERE from_concept = 'COMPUTE_EVENT' AND to_concept = 'COMPUTE_DECISION' AND relation_name = '触发' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_EVENT', 'COMPUTE_DECISION', '触发', '基数 0..1 : 0..N；尚未落库：当前 decision 没有触发事件外键；resource_event.causing_decision_id 表示反方向的事件来源。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_EVENT' AND to_concept = 'COMPUTE_DECISION' AND relation_name = '触发');
UPDATE bm_relation SET description = '基数 0..1 : 0..1；已有字段：decision.supersedes_decision_id；新决策→被替代旧决策，同申请同类型、无环且 asOf 递增（I-12）；完整执行检查待后续。' WHERE from_concept = 'COMPUTE_DECISION' AND to_concept = 'COMPUTE_DECISION' AND relation_name = '替代' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_DECISION', 'COMPUTE_DECISION', '替代', '基数 0..1 : 0..1；已有字段：decision.supersedes_decision_id；新决策→被替代旧决策，同申请同类型、无环且 asOf 递增（I-12）；完整执行检查待后续。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_DECISION' AND to_concept = 'COMPUTE_DECISION' AND relation_name = '替代');
UPDATE bm_relation SET description = '基数 1 : 1..N；已有字段：rule_check.decision_id；每次决策至少一项规则判定，实例完整性由后续审计检查。' WHERE from_concept = 'COMPUTE_DECISION' AND to_concept = 'COMPUTE_RULE_CHECK' AND relation_name = '包含' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_DECISION', 'COMPUTE_RULE_CHECK', '包含', '基数 1 : 1..N；已有字段：rule_check.decision_id；每次决策至少一项规则判定，实例完整性由后续审计检查。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_DECISION' AND to_concept = 'COMPUTE_RULE_CHECK' AND relation_name = '包含');
UPDATE bm_relation SET description = '基数 N : 1；已有字段：rule_check.rule_version_id → rule_version；必须是实际采用的规则版本（I-6）。' WHERE from_concept = 'COMPUTE_RULE_CHECK' AND to_concept = 'COMPUTE_RULE' AND relation_name = '引用' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_RULE_CHECK', 'COMPUTE_RULE', '引用', '基数 N : 1；已有字段：rule_check.rule_version_id → rule_version；必须是实际采用的规则版本（I-6）。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_RULE_CHECK' AND to_concept = 'COMPUTE_RULE' AND relation_name = '引用');
UPDATE bm_relation SET description = '基数 N : N；关联查询：rule_check_evidence；证据带来源、采集时间、有效期。' WHERE from_concept = 'COMPUTE_RULE_CHECK' AND to_concept = 'COMPUTE_EVIDENCE' AND relation_name = '依据' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_RULE_CHECK', 'COMPUTE_EVIDENCE', '依据', '基数 N : N；关联查询：rule_check_evidence；证据带来源、采集时间、有效期。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_RULE_CHECK' AND to_concept = 'COMPUTE_EVIDENCE' AND relation_name = '依据');
UPDATE bm_relation SET description = '基数 N : N；尚未落库：没有规则判定依赖关联表；不把 output 文本冒充可查询关系。' WHERE from_concept = 'COMPUTE_RULE_CHECK' AND to_concept = 'COMPUTE_RULE_CHECK' AND relation_name = '依赖' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_RULE_CHECK', 'COMPUTE_RULE_CHECK', '依赖', '基数 N : N；尚未落库：没有规则判定依赖关联表；不把 output 文本冒充可查询关系。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_RULE_CHECK' AND to_concept = 'COMPUTE_RULE_CHECK' AND relation_name = '依赖');
UPDATE bm_relation SET description = '基数 1 : 0..N；已有字段：execution_action.decision_id；批准不等于动作成功。' WHERE from_concept = 'COMPUTE_DECISION' AND to_concept = 'COMPUTE_ACTION' AND relation_name = '产生' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_DECISION', 'COMPUTE_ACTION', '产生', '基数 1 : 0..N；已有字段：execution_action.decision_id；批准不等于动作成功。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_DECISION' AND to_concept = 'COMPUTE_ACTION' AND relation_name = '产生');
UPDATE bm_relation SET description = '基数 0..1 : 0..N；已有字段：resource_event.causing_decision_id；事件也可来自硬件、监控或执行器，来源决策可空。' WHERE from_concept = 'COMPUTE_DECISION' AND to_concept = 'COMPUTE_EVENT' AND relation_name = '产生' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_DECISION', 'COMPUTE_EVENT', '产生', '基数 0..1 : 0..N；已有字段：resource_event.causing_decision_id；事件也可来自硬件、监控或执行器，来源决策可空。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_DECISION' AND to_concept = 'COMPUTE_EVENT' AND relation_name = '产生');
UPDATE bm_relation SET description = '基数 N : N；尚未落库：resource_event.impact_scope 可记影响人员，但没有正式通知关系或投递明细；不代表已经发送。' WHERE from_concept = 'COMPUTE_EVENT' AND to_concept = 'COMPUTE_EMPLOYEE' AND relation_name = '通知' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_EVENT', 'COMPUTE_EMPLOYEE', '通知', '基数 N : N；尚未落库：resource_event.impact_scope 可记影响人员，但没有正式通知关系或投递明细；不代表已经发送。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_EVENT' AND to_concept = 'COMPUTE_EMPLOYEE' AND relation_name = '通知');
UPDATE bm_relation SET description = '基数 N : N；尚未落库：impact_scope 可记团队，无正式通知关系或投递明细；harness 为业务通知对象。' WHERE from_concept = 'COMPUTE_EVENT' AND to_concept = 'COMPUTE_TEAM' AND relation_name = '通知' AND description = '算力域对象关系';
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description)
SELECT 'COMPUTE_EVENT', 'COMPUTE_TEAM', '通知', '基数 N : N；尚未落库：impact_scope 可记团队，无正式通知关系或投递明细；harness 为业务通知对象。' WHERE NOT EXISTS (SELECT 1 FROM bm_relation WHERE from_concept = 'COMPUTE_EVENT' AND to_concept = 'COMPUTE_TEAM' AND relation_name = '通知');
