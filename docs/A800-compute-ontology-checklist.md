# A800 算力对象、属性与关系核对清单

M-01 基线：算力本体评审稿 v0.7、数据库设计 v0.2、知识治理与数据治理报告（技术版）v0.1。

范围：补齐建模元数据。V3 基线为 18 类概念、19 项属性和 21 条关系；V4/V5 将其补齐为 18 类概念、138 项属性、30 条标准关系。本清单记录定义与真实数据来源。规则执行、调度、业务约束及新增字段映射属于后续条目。

## 语义边界

- 申请是需求；作业是一次实际尝试；常驻部署是持续提供服务的模型实例。
- 待生效预留只留资源，不持有卡组。模型 B 实际持有与训练预留 B 组可以同时存在；停服确认后才能交接。
- 概念 status（DRAFT/REVIEW/PUBLISHED/DEPRECATED）是定义的生命周期，不能当成对象业务状态。
- 属性标注“已有字段”“关联查询”“派生结果”“尚未落库”；登记元数据不等于新增运行服务或字段映射。
- 容量以字节计，已有日期以 UTC 保存；未知或未来字段不编造物理列。owner 是维护角色，不把建议分工当作人员授权。

## 18 类对象

| 编码 | 对象 | 维护角色 | 定义 | 数据来源 |
|---|---|---|---|---|
| COMPUTE_NODE | 节点 | 运维 | 纳管的物理服务器，包含卡组并提供共享内存和存储容量；当前仅一台八卡 A800。资产容量与实时水位分别登记，不将监控状态当作平台预留。 | `compute_node` |
| COMPUTE_GPU_GROUP | 卡组 | 运维 | 整组预留、持有、抢占和释放的单位。当前 A/B/C/FLEX 四组；实际持有与待生效预留可同时存在；组内未使用卡随组保留，不独立外借。 | `gpu_group` |
| COMPUTE_GPU_DEVICE | GPU | 运维 | 卡组中的单张 GPU。以 GPU UUID 稳定识别，节点 ID 与本地卡号标明当前位置。资产显存容量与运行健康来自不同来源，不能仅凭无进程就判定可分配。 | `gpu_device` |
| COMPUTE_DEPLOYMENT | 常驻部署 | 模型负责人 | 持续提供服务的模型实例，与一次训练作业不同。可按授权暂停，恢复使用已固定的部署修订配置；单卡模型 C 默认保留。当前每组最多一个未退役部署。 | `deployment / deployment_revision` |
| COMPUTE_REQUEST | 算力申请 | 平台 | 员工提出的一次资源需求，记录项目、期望任务类型与规格。通过、排队或拒绝是决策结果；申请可以对应多次历史作业，但同一时刻最多一个活跃作业。 | `compute_request` |
| COMPUTE_JOB | 作业 | 平台 | 申请产生的一次实际执行尝试，区别于申请和长期运行的常驻部署。重试创建新作业；一次作业最多一份分配，训练作业不可被其他作业抢占。 | `compute_job` |
| COMPUTE_ALLOCATION | 资源分配 | 平台 | 一次作业对整组资源的绑定与预留，保存预留容量及时间。PENDING 是待生效预留，只有 OCCUPIED 才通过持有区间取得卡组；组内未使用卡不外借。 | `gpu_allocation` |
| COMPUTE_HOLD_PERIOD | 持有区间 | 平台 | 记录谁从何时到何时实际持有卡组，持有主体恰好是常驻部署或占用分配之一。待生效预留不创建持有；主体、卡组和开始时间不可变，结束时间只能写一次，关闭后不可变。 | `hold_period` |
| COMPUTE_RULE | 规则 | 规则负责人 | 定义检查、规划、动作或条件写入的模板，不是某次执行结果。BeModel 的 bm_rule 管定义，算力 rule_version 存执行快照；规则编号与版本组合识别一次规则版本。 | `bm_rule / rule_version` |
| COMPUTE_RULE_CHECK | 规则判定 | 平台 | 某条指定版本规则在一次决策中的执行结果。result 表示规则是否通过，output 保存结构化产出；不能用规则是否通过代替申请结论或动作执行状态。 | `rule_check` |
| COMPUTE_DECISION | 决策 | 平台 | 对申请的一次阶段性判断，区分准入、提交和收尾。通过规则检查及预留成功才能批准，但批准不等于动作或作业启动成功；重判保留前序、替代关系与原证据。 | `decision` |
| COMPUTE_ACTION | 执行动作 | 平台 | 一次实际操作，例如通知、摘流、停服、启动和恢复。execution_action 是运行记录，区别于 bm_action 的动作定义；幂等编号、执行状态和回执用于核对是否真正完成。 | `execution_action` |
| COMPUTE_EVIDENCE | 证据 | 平台 | 一次判定采用的数据及其来源、采集时间和有效期。通过快照集合关联决策，保留来源引用与关键值；不能事后用最新监控覆盖历史证据。 | `evidence / evidence_snapshot` |
| COMPUTE_EVENT | 资源事件 | 平台 | 已经发生的资源变化或通知事实，来自决策、监控、硬件或执行器。事件不是操作指令；来源决策可为空，事件触发决策与决策产生事件是不同方向的关系。 | `resource_event` |
| COMPUTE_EMPLOYEE | 员工 | 身份数据维护人 | 以公司员工编号识别的申请人和通知对象，归属团队。当前 Demo 只登记基本信息，账号绑定、角色和部门不等于已实现的员工权限模型。 | `employee` |
| COMPUTE_TEAM | 团队 | 组织数据维护人 | 员工所属和项目参与的组织单元；可作为部署责任与通知对象。当前团队与项目使用关联表，团队类型尚未登记。 | `team` |
| COMPUTE_PROJECT | 项目 | 项目负责人 | 资源使用与成本归属的项目，记录负责人、团队和成本中心。v0.7 当前不设配额，不因项目额度拦截申请；用量由作业和分配汇总。 | `project` |
| COMPUTE_TASK_TYPE | 任务类型 | 规则负责人 | 任务模板，给出默认等级和资源规格；不是实际作业，也不是某个常驻模型。申请指定期望类型，作业记录实际类型，变更须有改派依据。 | `task_type` |

## 关键属性核对

现有 IDENTIFIER/UUID/VRAM_BYTES 编码与主键保留；复合编号和展示名称不误标为单字段全局唯一。以下内容由 V4 登记到属性定义，不新增业务表列。

### 节点（COMPUTE_NODE）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 节点稳定编号 | STRING | 已有字段：compute_node.node_key；节点稳定编号，唯一，不等于可变主机名。 |
| HOSTNAME | 主机名 | STRING | 已有字段：compute_node.hostname；资产登记的主机名。 |
| MEMORY_CAPACITY_BYTES | 内存总容量 | BIGINT | 已有字段：compute_node.memory_capacity_bytes；单位字节，非负。 |
| MEMORY_RESERVED_BYTES | 系统保留内存 | BIGINT | 已有字段：compute_node.memory_reserved_bytes；单位字节，非负，不用于任务分配。 |
| TEMPORARY_CAPACITY_BYTES | 临时存储容量 | BIGINT | 已有字段：compute_node.temporary_capacity_bytes；单位字节，非负。 |
| PERSISTENT_CAPACITY_BYTES | 持久存储容量 | BIGINT | 已有字段：compute_node.persistent_capacity_bytes；单位字节，非负。 |
| RESOURCE_WATERMARK | 内存与磁盘水位 | JSON | 关联查询：resource_observation.values_json，subject_kind=NODE；实时值须带单位、来源与采集时间，尚无真实采集。 |
| OBSERVED_AT | 最近采集时间 | DATE | 关联查询：resource_observation.observed_at；UTC，不能用接收时间代替采集时间。 |
| IP_ADDRESS | 节点地址 | STRING | 尚未落库：v0.7 的 IP 地址；当前 compute_node 没有对应列。 |
| MANAGEMENT_STATUS | 纳管状态 | ENUM | 尚未落库：节点是否纳管；不等于概念的 DRAFT/REVIEW/PUBLISHED/DEPRECATED。 |

### 卡组（COMPUTE_GPU_GROUP）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 节点内卡组编号 | STRING | 已有字段：gpu_group.group_code；仅与 node_id 组合唯一，不能单独当全局主键。 |
| GROUP_ID | 卡组记录编号 | BIGINT | 已有字段：gpu_group.id；全局记录主键。 |
| NAME | 卡组名称 | STRING | 已有字段：gpu_group.group_code；Demo 以组编号展示名称，无独立名称列。 |
| CURRENT_HOLDER | 当前持有者 | JSON | 关联查询：gpu_group.current_hold_id → hold_period；主体为部署或占用分配，两者恰选一个；不是待生效预留。 |
| PENDING_RESERVATION | 待生效预留 | JSON | 关联查询：gpu_group.pending_allocation_id → gpu_allocation；PENDING 只留资源，尚未取得持有权。 |
| STATUS | 卡组业务状态 | ENUM | 派生结果：根据持有、待生效预留和观测组合解释；可能同时为已持有和已预留，不维护单一状态列，也不是概念发布状态。 |
| DIRECTLY_ALLOCATABLE | 可直接分配 | ENUM | 派生结果：现有 ComputeGroupQuery 综合持有、预留和有效 GPU 健康/占用观测返回 true/false；不代表申请获批。 |
| RESERVED_CARDS | 随组保留卡 | JSON | 派生结果：组内全部卡减作业使用卡；使用卡来自 allocation_gpu。禁止独立维护或再次分配，完整查询待 M-06。 |
| INTERCONNECT | 互联类型 | STRING | 尚未落库：v0.7 互联类型，不能从 GPU 数量猜测。 |
| TOPOLOGY | 拓扑描述 | STRING | 尚未落库：卡组连接拓扑，后续由资产登记补充。 |

### GPU（COMPUTE_GPU_DEVICE）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| UUID | GPU UUID | STRING | 已有字段：gpu_device.gpu_uuid；稳定身份，唯一；Demo 使用样例 UUID，实机必须核对。 |
| GPU_ID | 设备记录编号 | BIGINT | 已有字段：gpu_device.id；关联使用的内部主键。 |
| GPU_INDEX | 本地卡号 | NUMBER | 已有字段：gpu_device.gpu_index；与 node_id 组合唯一，不单独代表 GPU 身份。 |
| MODEL | GPU 型号 | STRING | 已有字段：gpu_device.model；资产登记，例如 A800。 |
| VRAM_BYTES | 显存容量 | BIGINT | 已有字段：gpu_device.vram_bytes；物理显存容量，单位字节，非负，不是当前空闲显存。 |
| HEALTHY | 健康状态 | ENUM | 关联查询：resource_observation.values_json.healthy，subject_kind=GPU；true/false，未知或过期不能当健康。 |
| OCCUPIED | 实际占用 | ENUM | 关联查询：resource_observation.values_json.occupied；true/false，与平台预留分别保存。 |
| OBSERVED_AT | 最近采集时间 | DATE | 关联查询：resource_observation.observed_at；UTC，须核对 valid_until 和观测状态。 |

### 常驻部署（COMPUTE_DEPLOYMENT）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 部署名称 | STRING | 已有字段：deployment.name；展示名，不唯一，不是部署主键。 |
| DEPLOYMENT_ID | 部署记录编号 | BIGINT | 已有字段：deployment.id；常驻部署的稳定记录主键。 |
| PREEMPTIBLE | 允许抢占 | ENUM | 已有字段：deployment.preemptible；true/false，还须检查任务等级、对象类型和默认保留限制。 |
| STATUS | 部署业务状态 | ENUM | 已有字段：deployment.status；当前样例 RUNNING，默认 REGISTERED，退役 RETIRED；v0.7 停服/恢复/故障等流程状态待执行模块落实，不是概念发布状态。 |
| RESOURCE_BASELINE | 资源基线 | JSON | 关联查询：deployment.current_revision_id → deployment_revision 的 memory_bytes/temporary_bytes/persistent_bytes；单位字节。 |
| RESTORE_CONFIG | 恢复配置 | JSON | 关联查询：deployment_revision.image_ref/command_args/mounts；抢占流程用 workflow_run.restore_revision_id 固定原版本，不随当前版本变化。 |
| HEALTH_CHECK | 健康检查配置 | JSON | 关联查询：deployment_revision.health_check；定义启动后如何确认服务可用，尚无真实执行。 |
| REVISION | 当前配置版本 | NUMBER | 关联查询：deployment.current_revision_id → deployment_revision.version；不同于概念版本。 |
| RESIDENT | 常驻属性 | ENUM | 派生结果：属于常驻部署类型即为常驻服务；不等于当前正在运行，不新增常驻标记列。 |

### 算力申请（COMPUTE_REQUEST）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 申请编号 | BIGINT | 已有字段：compute_request.id；唯一记录主键。 |
| GPU_COUNT | 申请卡数 | NUMBER | 已有字段：compute_request.requested_gpu_count；正整数，v0.7 的 1–4 卡进入目标选择，五卡及以上排队，不跨组拼卡。 |
| RESOURCE_SPEC | 申请资源规格 | JSON | 已有字段：compute_request.requested_resource_spec；vramBytesPerGpu/memoryBytes/temporaryBytes/persistentBytes 单位字节，gpuInterconnect 可选；卡数单独存。 |
| EFFECTIVE_RESOURCE_SPEC | 生效资源规格 | JSON | 派生结果：任务类型默认值加申请显式覆盖；实际作业保存 compute_job.effective_resource_spec，不能冒充已实现的默认值合并服务。 |
| LAUNCH_SPEC | 启动要求 | JSON | 已有字段：compute_request.launch_spec；镜像、命令及挂载等申请输入，登记不代表已执行。 |
| STATUS | 申请业务状态 | ENUM | 已有字段：compute_request.status；默认 SUBMITTED，样例 APPROVED；v0.7 的排队/拒绝/取消等由后续流程受控更新，不是概念发布状态。 |
| SUBMITTED_AT | 提交时间 | DATE | 已有字段：compute_request.submitted_at；UTC。 |
| CLIENT_REQUEST_KEY | 客户端请求编号 | STRING | 已有字段：compute_request.client_request_key；同一员工下唯一，与请求指纹一起用于识别重复提交。 |
| QUEUED_AT | 排队登记时间 | DATE | 关联查询：compute_queue.submitted_at；UTC，未排队时可无记录，不等于等待时长。 |
| EXPECTED_START_AT | 期望开始时间 | DATE | 尚未落库：v0.7 期望开始时间；不伪造 compute_request 对应列。 |
| EXPECTED_END_AT | 期望结束时间 | DATE | 尚未落库：v0.7 期望结束时间，不能当实际结束时间。 |

### 作业（COMPUTE_JOB）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 作业编号 | BIGINT | 已有字段：compute_job.id；一次执行尝试的唯一记录主键。 |
| GPU_COUNT | 实际用卡数 | NUMBER | 已有字段：compute_job.effective_gpu_count；当前业务库限制 1–4，激活时须与使用卡明细数量一致。 |
| RESOURCE_SPEC | 实际资源规格 | JSON | 已有字段：compute_job.effective_resource_spec；资源量按字节，实际采用的规格，不覆盖原申请。 |
| STATUS | 作业业务状态 | ENUM | 已有字段：compute_job.status；默认 PENDING，启动/运行/结束等状态待执行模块实现，不是申请批准结果或概念发布状态。 |
| STARTED_AT | 实际开始时间 | DATE | 已有字段：compute_job.started_at；UTC，尚未开始可为空。 |
| ENDED_AT | 实际结束时间 | DATE | 已有字段：compute_job.ended_at；UTC，未结束可为空。 |
| RUNTIME_REF | 运行实例引用 | STRING | 已有字段：compute_job.runtime_ref；运行环境实例引用，完整容器绑定历史待 runtime_binding 实机扩展。 |
| PRIORITY | 调度等级 | NUMBER | 关联查询：compute_job.task_type_id → task_type.priority；当前两级，实际排队等级另存 compute_queue.priority，未保存作业独立等级快照。 |
| ATTEMPT_NUMBER | 尝试序号 | NUMBER | 尚未落库：v0.7 尝试序号；作业编号不是同申请的尝试序号。 |

### 资源分配（COMPUTE_ALLOCATION）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 分配编号 | BIGINT | 已有字段：gpu_allocation.id；唯一主键，同一作业最多一份分配。 |
| STATUS | 分配业务状态 | ENUM | 已有字段：gpu_allocation.status；PENDING=待生效、OCCUPIED=占用、RELEASED=释放、CANCELLED=已取消；待生效不持有，前两者计入预留。 |
| MEMORY_BYTES | 预留内存 | BIGINT | 已有字段：gpu_allocation.memory_bytes；单位字节，非负。 |
| TEMPORARY_BYTES | 预留临时存储 | BIGINT | 已有字段：gpu_allocation.temporary_bytes；单位字节，非负。 |
| PERSISTENT_BYTES | 预留持久存储 | BIGINT | 已有字段：gpu_allocation.persistent_bytes；单位字节，非负。 |
| RESERVED_AT | 预留时间 | DATE | 已有字段：gpu_allocation.reserved_at；UTC，不代表取得持有权。 |
| ACTIVATED_AT | 生效时间 | DATE | 已有字段：gpu_allocation.activated_at；UTC，确认交接后生效。 |
| CLOSED_AT | 释放或取消时间 | DATE | 已有字段：gpu_allocation.closed_at；UTC，未关闭可为空。 |
| CLOSE_REASON | 释放或取消原因 | STRING | 已有字段：gpu_allocation.close_reason；保留关闭原因。 |

### 持有区间（COMPUTE_HOLD_PERIOD）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 持有区间编号 | BIGINT | 已有字段：hold_period.id；唯一主键，关闭后不得复用。 |
| HOLDER | 持有主体 | JSON | 关联查询：hold_period.deployment_id 或 allocation_id，恰好一个非空；主体是对象关系，不存拼接名字。 |
| DEPLOYMENT_REVISION | 持有时部署配置 | NUMBER | 关联查询：hold_period.deployment_revision_id → deployment_revision；部署持有必须指定配置版本，不是当前任意最新版本。 |
| STARTED_AT | 持有开始时间 | DATE | 已有字段：hold_period.started_at；UTC，创建后不可修改。 |
| ENDED_AT | 持有结束时间 | DATE | 已有字段：hold_period.ended_at；UTC，只允许从空写一次，结束不得早于开始；本次只登记语义，额外防篡改保护属于 M-03。 |

### 规则（COMPUTE_RULE）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 规则编号 | STRING | 已有字段：rule_version.rule_code；跨版本重复，须与 version 组合识别执行版本；对应 bm_rule.rule_code。 |
| VERSION | 规则版本 | NUMBER | 已有字段：rule_version.version；规则编号加版本唯一，不覆盖旧版。 |
| STAGE | 适用阶段 | ENUM | 已有字段：rule_version.stage；准入、方案、提交、执行、收尾或全程；具体编码由后续规则模块统一。 |
| RULE_TYPE | 规则类型 | ENUM | 已有字段：rule_version.rule_type；校验型、规划型、动作型、约束型；不等于规则的发布状态。 |
| DEFINITION | 判定定义 | JSON | 已有字段：rule_version.definition；程序采用的定义和参数，登记不代表可直接执行任意表达式。 |
| REASON_CODES | 原因码 | JSON | 已有字段：rule_version.reason_codes；描述失败或分支原因。 |
| OWNER | 规则维护人 | STRING | 关联查询：rule_version.owner_employee_id → employee；BeModel 概念 owner 是维护角色，不代替实例负责人。 |
| EFFECTIVE_FROM | 生效开始时间 | DATE | 已有字段：rule_version.effective_from；UTC。 |
| EFFECTIVE_UNTIL | 生效截止时间 | DATE | 已有字段：rule_version.effective_until；UTC，可空，非空时晚于开始时间。 |

### 规则判定（COMPUTE_RULE_CHECK）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 规则判定编号 | BIGINT | 已有字段：rule_check.id；唯一主键。 |
| RESULT | 规则判定结果 | ENUM | 已有字段：rule_check.result；PASS/FAIL/NOT_APPLICABLE/ERROR；不是决策 outcome 或动作 status。 |
| OUTPUT | 结构化产出 | JSON | 已有字段：rule_check.output；计划引用、动作引用或分支等，可为空，不把结论只写自然语言。 |
| REASON_CODE | 判定原因码 | STRING | 已有字段：rule_check.reason_code；可追溯的分支或失败原因。 |
| RULE_VERSION | 采用的规则版本 | NUMBER | 关联查询：rule_check.rule_version_id → rule_version；必须保留实际使用版本。 |
| KIND | 判定所属阶段 | ENUM | 关联查询：rule_check.decision_id → decision.kind；ADMISSION/SUBMISSION/CLOSURE，无独立 kind 列。 |
| CHECKED_AT | 检查时间 | DATE | 已有字段：rule_check.checked_at；UTC，不替代决策统一 as_of。 |

### 决策（COMPUTE_DECISION）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 决策编号 | BIGINT | 已有字段：decision.id；唯一主键，历史判断保留。 |
| KIND | 决策类型 | ENUM | 已有字段：decision.kind；ADMISSION=准入、SUBMISSION=提交、CLOSURE=收尾。 |
| OUTCOME | 决策结论 | ENUM | 已有字段：decision.outcome；准入=通过/不通过，提交=批准/排队/拒绝，收尾=恢复/让位/等待资源；具体编码由后续判定模块落实。 |
| REASON_CODE | 决策原因码 | STRING | 已有字段：decision.reason_code；解释本次结论。 |
| AS_OF | 统一判断时间 | DATE | 已有字段：decision.as_of；UTC，一次决策一个时间点，所有证据按此核对有效期。 |
| SNAPSHOT_ID | 证据集合编号 | BIGINT | 已有字段：decision.snapshot_id；对应 evidence_snapshot，与 as_of 一致。 |
| TARGET_KIND | 目标类型 | ENUM | 已有字段：decision.target_kind；与 target_id 同时有值或同时为空，目标可为部署、分配、GPU、卡组等。 |
| TARGET_ID | 目标记录编号 | BIGINT | 已有字段：decision.target_id；须与 target_kind 一起解读，不能跨类型直接比较。 |
| PLAN | 操作计划 | JSON | 已有字段：decision.plan；v0.7 SchedulePlan 值对象，不单独增加计划概念或任务表。 |

### 执行动作（COMPUTE_ACTION）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 动作编号 | BIGINT | 已有字段：execution_action.id；一次运行操作的主键，不是 bm_action.action_code。 |
| ACTION_TYPE | 操作类型 | ENUM | 已有字段：execution_action.action_type；通知、摘流、停服、启动、停止、恢复等，执行能力后续实现。 |
| TARGET_KIND | 操作目标类型 | ENUM | 已有字段：execution_action.target_kind；配合 target_id 引用实际目标。 |
| TARGET_ID | 操作目标编号 | BIGINT | 已有字段：execution_action.target_id；不是可直接执行的服务器命令。 |
| STATUS | 动作执行状态 | ENUM | 已有字段：execution_action.status；默认 PENDING，业务含待执行/执行中/未知/成功/失败/已补偿；完整编码待执行模块落实，不是规则 result 或概念发布状态。 |
| IDEMPOTENCY_KEY | 幂等编号 | STRING | 已有字段：execution_action.idempotency_key；唯一，同一逻辑操作重复请求须核对原结果。 |
| STARTED_AT | 执行开始时间 | DATE | 已有字段：execution_action.started_at；UTC。 |
| FINISHED_AT | 结果确认时间 | DATE | 已有字段：execution_action.finished_at；UTC。 |
| RECEIPT | 操作回执 | JSON | 已有字段：execution_action.receipt；执行器返回的事实，不能用已发指令代替成功。 |

### 证据（COMPUTE_EVIDENCE）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 证据编号 | BIGINT | 已有字段：evidence.id；唯一主键。 |
| SOURCE | 证据来源 | STRING | 已有字段：evidence.source；监控、日志或平台来源，不能缺省猜测。 |
| SOURCE_RECORD_REF | 来源记录引用 | STRING | 已有字段：evidence.source_record_ref；原始记录引用，配合关键值复核。 |
| OBSERVED_AT | 采集时间 | DATE | 已有字段：evidence.observed_at；UTC。 |
| VALID_UNTIL | 有效截止时间 | DATE | 已有字段：evidence.valid_until；UTC，不早于 observed_at；还需按规则检查最大年龄。 |
| SNAPSHOT_ID | 证据集合编号 | BIGINT | 已有字段：evidence.snapshot_id；对应 evidence_snapshot，同次决策按统一 as_of 检查。 |
| VALUES | 证据关键值 | JSON | 已有字段：evidence.values_json；引用加关键值和单位，不复制全量日志，不以零冒充失败采集。 |

### 资源事件（COMPUTE_EVENT）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 事件编号 | BIGINT | 已有字段：resource_event.id；唯一主键。 |
| EVENT_TYPE | 事件类型 | STRING | 已有字段：resource_event.event_type；资源变化、通知等事件分类。 |
| SOURCE | 事件来源 | STRING | 已有字段：resource_event.source；不能为空，监控、平台或执行器等。 |
| IMPACT_SCOPE | 影响范围 | JSON | 已有字段：resource_event.impact_scope；受影响资源、员工和团队，现有 JSON 不等于已建通知关系表。 |
| OCCURRED_AT | 发生时间 | DATE | 已有字段：resource_event.occurred_at；UTC。 |
| PAYLOAD | 事件内容 | JSON | 已有字段：resource_event.payload；事件事实，不等于命令回执。 |
| DELIVERY_STATUS | 通知投递状态 | ENUM | 尚未落库：v0.7 投递状态；当前 resource_event 无独立投递字段或投递表，不能推断为已送达。 |

### 员工（COMPUTE_EMPLOYEE）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 员工编号 | STRING | 已有字段：employee.employee_code；工号稳定且唯一，不靠姓名匹配。 |
| NAME | 员工姓名 | STRING | 已有字段：employee.name；展示名，不作为身份主键。 |
| EMAIL | 通知邮箱 | STRING | 已有字段：employee.email；可空，登记邮箱不代表已接邮件发送。 |
| ACCOUNT | 登录账号 | STRING | 尚未落库：公司账号与 employee 的绑定；不把平台管理员账号当作员工账号。 |
| ROLE | 员工角色 | STRING | 尚未落库：员工业务角色，当前只复用平台登录角色。 |
| DEPARTMENT | 部门 | STRING | 尚未落库：部门登记；不能把 employee.team_id 默认解释为部门。 |

### 团队（COMPUTE_TEAM）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 团队编号 | STRING | 已有字段：team.team_code；稳定且唯一。 |
| NAME | 团队名称 | STRING | 已有字段：team.name；展示名称。 |
| TYPE | 团队类型 | STRING | 尚未落库：v0.7 团队类型，当前 team 无 type 列。 |

### 项目（COMPUTE_PROJECT）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 项目编号 | STRING | 已有字段：project.project_code；稳定且唯一。 |
| NAME | 项目名称 | STRING | 已有字段：project.name；展示名称。 |
| COST_CENTER | 成本中心 | STRING | 已有字段：project.cost_center；可空，用于成本归属而非财务审批。 |
| USAGE | 项目资源用量 | JSON | 派生结果：按 compute_request、compute_job 与 gpu_allocation 汇总，整组锁定卡数用于报表；完整统计待后续实现。 |
| NOTES | 项目备注 | STRING | 尚未落库：v0.7 备注，当前 project 无备注列。 |

### 任务类型（COMPUTE_TASK_TYPE）

| 属性编码 | 名称 | 类型 | 来源与说明 |
|---|---|---|---|
| IDENTIFIER | 任务类型编号 | STRING | 已有字段：task_type.type_code；稳定且唯一。 |
| NAME | 任务类型名称 | STRING | 已有字段：task_type.name；例如训练。 |
| PRIORITY | 默认调度等级 | NUMBER | 已有字段：task_type.priority；当前训练高于常驻部署，同级排队，数值越大越优先，等级为配置。 |
| DEFAULT_RESOURCE_SPEC | 默认资源规格 | JSON | 已有字段：task_type.default_resource_spec；容量字段为字节，每卡显存要求与卡数分别解释。 |
| MAX_DURATION | 运行时长上限 | NUMBER | 尚未落库：v0.7 时长上限；后续确定单位和字段，当前不能据此宣称已限制任务时长。 |

## 30 条标准关系

基数按 v0.7 保留在关系说明，不扩展通用关系表。它是类型约定，不代表运行时全部约束已实现。对照 V3，V5 新增 9 条标准关系，另将 8 条初始化关系改名或调整方向。若已有同名标准关系或旧关系被用户改过，则保留用户内容并补齐标准关系；这类库可多于 30 条，不能为凑数量删除自建关系。

| 序号 | 起点 | 关系 | 终点 | 基数 | 来源与限制 | V2 缺口 |
|---|---|---|---|---|---|---|
| 1 | COMPUTE_NODE | 包含 | COMPUTE_GPU_GROUP | 1 : 1..N | 已有字段：gpu_group.node_id；节点包含卡组。 | 补充说明 |
| 2 | COMPUTE_GPU_GROUP | 包含 | COMPUTE_GPU_DEVICE | 1 : 1..N | 已有字段：gpu_device.group_id；GPU 归属一个卡组。 | 补充说明 |
| 3 | COMPUTE_GPU_GROUP | 承载 | COMPUTE_DEPLOYMENT | 1 : 0..N | 已有字段：deployment.group_id；类型保留历史基数，首期每组最多一个未退役部署（I-4/I-14）。 | 补充说明 |
| 4 | COMPUTE_DEPLOYMENT | 归属 | COMPUTE_PROJECT | N : 1 | 已有字段：deployment.project_id；用于通知与成本归属。 | 新增 |
| 5 | COMPUTE_DEPLOYMENT | 负责 | COMPUTE_TEAM | N : 1 | 尚未落库：当前仅 deployment.owner_employee_id 记录个人负责人，不能据此假定其团队即责任团队。 | 新增 |
| 6 | COMPUTE_EMPLOYEE | 属于 | COMPUTE_TEAM | N : 1 | 已有字段：employee.team_id；所属团队，不是部门权限。 | 补充说明 |
| 7 | COMPUTE_TEAM | 参与 | COMPUTE_PROJECT | N : N | 关联查询：project_team；项目与团队多对多。 | 补充说明 |
| 8 | COMPUTE_EMPLOYEE | 提出 | COMPUTE_REQUEST | 1 : 0..N | 已有字段：compute_request.employee_id；员工提交申请。 | 调整：EMPLOYEE→REQUEST→发起 |
| 9 | COMPUTE_PROJECT | 归属 | COMPUTE_REQUEST | 1 : 0..N | 已有字段：compute_request.project_id；方向按 v0.7 为项目→申请，不增加反向重复标准边。 | 调整：REQUEST→PROJECT→归属 |
| 10 | COMPUTE_REQUEST | 指定（期望） | COMPUTE_TASK_TYPE | N : 1 | 已有字段：compute_request.task_type_id；与作业实际类型分开。 | 调整：REQUEST→TASK_TYPE→采用 |
| 11 | COMPUTE_REQUEST | 产生 | COMPUTE_JOB | 1 : 0..N | 已有字段：compute_job.request_id；历史多次尝试，同时最多一个活跃作业（I-1）。 | 补充说明 |
| 12 | COMPUTE_JOB | 实例化（实际） | COMPUTE_TASK_TYPE | N : 1 | 已有字段：compute_job.task_type_id；不一致需改派决策（I-8），当前尚无改派执行能力。 | 新增 |
| 13 | COMPUTE_JOB | 占用 | COMPUTE_ALLOCATION | 1 : 0..1 | 已有字段：gpu_allocation.job_id；一次作业最多一份分配（I-2），关系名不意味着分配已经生效。 | 补充说明 |
| 14 | COMPUTE_ALLOCATION | 绑定 | COMPUTE_GPU_GROUP | N : 1 | 已有字段：gpu_allocation.group_id；按整组预留和释放。 | 补充说明 |
| 15 | COMPUTE_ALLOCATION | 使用 | COMPUTE_GPU_DEVICE | N : 0..N | 关联查询：allocation_gpu；待生效可为零，生效后数量等于作业实际用卡数（I-11）。 | 补充说明 |
| 16 | COMPUTE_HOLD_PERIOD | 持有 | COMPUTE_GPU_GROUP | N : 1 | 已有字段：hold_period.group_id；同组未结束持有最多一个，待生效分配不持有（I-5/I-21）。 | 调整：HOLD_PERIOD→GPU_GROUP→记录 |
| 17 | COMPUTE_HOLD_PERIOD | 持有主体 | COMPUTE_DEPLOYMENT | N : 0..1 | 已有字段：hold_period.deployment_id；与分配主体互斥且恰好一个，部署持有绑定配置版本。 | 调整：HOLD_PERIOD→DEPLOYMENT→部署持有者 |
| 18 | COMPUTE_HOLD_PERIOD | 持有主体 | COMPUTE_ALLOCATION | N : 0..1 | 已有字段：hold_period.allocation_id；与部署主体互斥且恰好一个，只能是占用分配。 | 调整：HOLD_PERIOD→ALLOCATION→分配持有者 |
| 19 | COMPUTE_REQUEST | 触发 | COMPUTE_DECISION | 1 : 0..N | 已有字段：decision.request_id；一申请可有多次、多个类型的判断。 | 调整：REQUEST→DECISION→产生 |
| 20 | COMPUTE_DECISION | 前序 | COMPUTE_DECISION | 0..1 : 0..N | 已有字段：decision.previous_decision_id；按前序决策→后续决策反查，单条后续决策至多一个前序，不等于替代。 | 新增 |
| 21 | COMPUTE_EVENT | 触发 | COMPUTE_DECISION | 0..1 : 0..N | 尚未落库：当前 decision 没有触发事件外键；resource_event.causing_decision_id 表示反方向的事件来源。 | 新增 |
| 22 | COMPUTE_DECISION | 替代 | COMPUTE_DECISION | 0..1 : 0..1 | 已有字段：decision.supersedes_decision_id；新决策→被替代旧决策，同申请同类型、无环且 asOf 递增（I-12）；完整执行检查待后续。 | 新增 |
| 23 | COMPUTE_DECISION | 包含 | COMPUTE_RULE_CHECK | 1 : 1..N | 已有字段：rule_check.decision_id；每次决策至少一项规则判定，实例完整性由后续审计检查。 | 补充说明 |
| 24 | COMPUTE_RULE_CHECK | 引用 | COMPUTE_RULE | N : 1 | 已有字段：rule_check.rule_version_id → rule_version；必须是实际采用的规则版本（I-6）。 | 补充说明 |
| 25 | COMPUTE_RULE_CHECK | 依据 | COMPUTE_EVIDENCE | N : N | 关联查询：rule_check_evidence；证据带来源、采集时间、有效期。 | 补充说明 |
| 26 | COMPUTE_RULE_CHECK | 依赖 | COMPUTE_RULE_CHECK | N : N | 尚未落库：没有规则判定依赖关联表；不把 output 文本冒充可查询关系。 | 新增 |
| 27 | COMPUTE_DECISION | 产生 | COMPUTE_ACTION | 1 : 0..N | 已有字段：execution_action.decision_id；批准不等于动作成功。 | 调整：DECISION→ACTION→触发 |
| 28 | COMPUTE_DECISION | 产生 | COMPUTE_EVENT | 0..1 : 0..N | 已有字段：resource_event.causing_decision_id；事件也可来自硬件、监控或执行器，来源决策可空。 | 补充说明 |
| 29 | COMPUTE_EVENT | 通知 | COMPUTE_EMPLOYEE | N : N | 尚未落库：resource_event.impact_scope 可记影响人员，但没有正式通知关系或投递明细；不代表已经发送。 | 新增 |
| 30 | COMPUTE_EVENT | 通知 | COMPUTE_TEAM | N : N | 尚未落库：impact_scope 可记团队，无正式通知关系或投递明细；harness 为业务通知对象。 | 新增 |

## 状态与实际支持范围

- 分配的 PENDING/OCCUPIED/RELEASED/CANCELLED、规则结果 PASS/FAIL/NOT_APPLICABLE/ERROR 已有数据库取值约束。
- 申请默认 SUBMITTED、样例 APPROVED；作业默认 PENDING；部署默认 REGISTERED、样例 RUNNING、退役 RETIRED。其他流程状态仅登记 v0.7 含义，不声称已有完整编码与状态机。
- 决策按 ADMISSION/SUBMISSION/CLOSURE 区分，outcome 按类型解释；动作 status 是执行结果，与规则 result 分开。
- 部署责任团队、事件触发决策、规则判定依赖和正式通知关系尚无独立存储。个人负责人、事件来源和影响 JSON 不能替代这些关系。

## 实施与核验

1. V4 完善概念定义与维护角色，补充上述属性；不修改概念 ID、编码、发布状态、历史快照和已有字段映射。
2. V5 对齐 30 条标准关系，只改 V2 原始初始化关系；保留可原位更新的 ID，用户自建关系和历史快照不删除。
3. 验证新库初始化、从 V3 升级、再次迁移无变更；概念详情返回完整属性和关系，卡组样例及映射保留。

依据文件：`A800算力本体-评审稿-v0.7.md` §3–4；`A800算力Agent数据库设计-v0.2.md` §8–9；`A800知识治理与数据治理报告-技术版-v0.1.md` §1–2。这些方案位于算力 Agent 文档目录，核对结论集中保存在本文件。

## 自动验收

- `ComputeOntologyMigrationTest`：隔离 MySQL 8 测试库中验证全新初始化、V3 升级、再次迁移无变化、原编号/映射/历史快照保留；逐条核对 30 条标准关系，并验证用户关系和同名冲突的保留。
- `ComputeMysqlSmokeTest`：隔离库启动后端，通过真实登录和 HTTP 概念详情查询检查 18 类对象、新属性与关系方向；继续验证八卡、B 组持有与预留并存，以及过期观测不可分配。
- 两者只创建和删除随机 `m01_test_*` 库，不迁移或删除连接地址中的原有数据库。凭据只通过环境变量提供。
- M-01 只登记语义；关系基数、预留和状态说明不能当成对应执行能力已经完成。
