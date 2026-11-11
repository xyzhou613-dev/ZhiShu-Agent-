# M-02 算力规则管理与确定性检查

业务基线：A800 本体 v0.7；改造清单 v0.2 的 M-02。保留 M-01 的 18 类对象、138 项属性、30 条关系，以及现有两库划分。

## 交付范围

- `V6__compute_rules.sql` 将原始五条草稿原位补全，并补齐另外九条，合计 14 条；不包含已废止的 R-09。
- 规则定义继续保存在 `bm_rule`。JSON 记录阶段、类型、输入说明、输出、原因码、失败去向、流程位置、实现版本、参数和教学样例。执行时读取数据库定义，不执行自然语言、SQL、SpEL 或任意脚本。
- `ComputeRuleEvaluator` 实现 14 个确定性检查；`ComputeRuleService` 检查编号与处理函数绑定、实现版本和页面使用的规则版本。
- 现有“本体管理 → 规则 → 详情”增加算力输入说明、对应处理函数和只读演练。编辑后版本加一、回到草稿；旧版本编辑被拒绝。状态流转使用版本与状态条件写入，防止覆盖并发修改。
- 医疗 QC 规则的表达式编辑保留原有路径，算力 JSON 不会被 QC 编辑器清空。

这项交付不创建真实申请、不进行资源预留、不连接节点执行器、不发送通知，也不写算力决策或规则执行快照。M-04 负责发布与激活执行快照，N-02/N-03 负责正式判定及服务端证据，N-07/N-08 负责实际预留事务与原子交接，N-11/N-12 负责持久流程与动作。这些后续能力应调用相同的检查逻辑，不能把页面演练返回值当作批准依据。

## 规则到代码、流程的对照

以下方法位于 `compute/ComputeRuleEvaluator.java`。

| 规则 | 实际检查位置 | 输入/判定重点 | 对接流程位置 | 主要不通过原因 |
|---|---|---|---|---|
| R-01 | `specification` | 完整规格与正整数卡数；1–4 可选目标，≥5 排队 | 提交、规格校验 | `DENY_SPEC`、`QUEUE_NO_TARGET` |
| R-02 | `capacity(f,false)` | 三类主机资源分项相加后不超过容量；不计将暂停部署 | 方案、锁内容量重查 | `QUEUE_NO_CAPACITY` |
| R-03 | `resources` | 每卡显存、主机资源、互联要求，不能合并多卡显存满足单卡需求 | 方案适配 | `QUEUE_RESOURCE_SHORT` |
| R-04 | `selectGroup` | 单卡 FLEX 否则 B、双卡 B、三四卡 A；不借用其他大组 | 目标选择 | `QUEUE_NO_TARGET` |
| R-05 | `actionGuard` | 预留有效、通知、摘流、在途归零、停服确认、释放、交接回执的顺序 | NOTIFY/DRAIN/STOP/HANDOVER/START_JOB | `NOTIFY_FAILED`、`ABORT_INFLIGHT`、`QUEUE_CONFLICT` |
| R-06 | `closure` | 确认退出后重跑候选的 R-12/02/03/14，否则跑 R-15 | 收尾：让位/恢复/等待 | 事实错误为 `ERROR`；分支见 `output.outcome` |
| R-07 | `reservationReceipt` | 校验真实事务回执、队首与容量重查、待生效唯一和持有唯一 | 预留事务提交后 | `QUEUE_CONFLICT`、`DENY_DUPLICATE` |
| R-08 | `notification` | 非空接收对象与渠道回执；抢占前失败撤销，恢复后失败重试通知 | 抢占前、恢复后 | `NOTIFY_FAILED` |
| R-10 | `audit` | 快照、判断时间、逐条规则版本和结果、有效引用到本集合的证据路径 | 每次决策提交前 | `AUDIT_INCOMPLETE` |
| R-11 | `freshness` | 全部证据的时间区间和最大年龄，边界含等号 | 每次决策前 | `QUEUE_STALE_EVIDENCE` |
| R-12 | `queueHead` | 等级降序、提交时间升序、编号升序；只允许队首 | 同组仲裁与锁内重查 | `QUEUE_NOT_HEAD` |
| R-13 | `preemption` | TRAIN 严格高于部署、允许抢占、仅 A/B 多卡部署；作业不可抢、单卡保留 | 抢占授权、动作前 | `QUEUE_NOT_PREEMPTIBLE` |
| R-14 | `health` | 选择组内不重复的健康卡，数量等于要求；未知不按健康 | 启动、让位复查 | `QUEUE_GROUP_UNHEALTHY` |
| R-15 | `restore` | 全组健康，恢复投影不超容量 | 恢复判定、启动前 | `RECOVERY_UNHEALTHY`、`RECOVERY_NO_CAPACITY` |

共同结果为 `PASS/FAIL/NOT_APPLICABLE/ERROR`，返回规则定义版本和 `a800-m02-v1` 实现版本。未知、缺失或类型错误的事实不能默认为零、空闲或健康。

R-06 的 `PASS` 表示“分支已经成功计算”，不等于恢复获准；必须读取 `output.outcome=HANDOFF/RESTORE/WAIT_RESOURCE`。R-13 不需要抢占时返回 `NOT_APPLICABLE`。R-07 的 `PASS` 仅说明所提供的提交回执满足条件；处理器本身不取得数据库锁、不创建预留，调用方不得自行伪造布尔回执。

## 页面使用

1. 登录平台，进入“本体管理 → 规则”。
2. 算力规则的驱动方式显示“算力检查”；点击“详情”。
3. 查看输入、原因码、处理函数、流程位置和实现版本。
4. 使用教学样例，或修改样例验证不通过的分支，点击“检查这些条件”。
5. 对照 `result`、`reasonCode` 和 `output` 阅读结果。演练不形成决策或动作。

样例时间是固定教学时间，不是当前 A800 监控。R-11 初始参数 `maxAgeSeconds=30` 对应现有 GPU 教学观测；正式运行时须由规则负责人确认不同来源的时效，再经 M-04 发布版本。页面传入同名 `maxAgeSeconds` 不能改变数据库规则参数。

R-02/R-15 输入的容量应扣除系统保留；输入分项必须由后端账本服务在同一快照中计算。新预留不得再次包含在有效预留合计中。正式调度不接受用户提供的旧 `asOf`，也不能用本演练接口提交的事实替代真实监控。

## 接口

- `GET /api/compute/rules`：14 个编号到数据库定义、处理函数、检查可用性的对应；缺失或人工自定义不兼容时明确标记不可演练，不假装已经接入。
- `POST /api/compute/rules/{code}/preview`：`{"ruleVersion": 当前定义版本, "facts": {...}}`。现有权限规则限定管理员、编辑员；未登录拒绝。
- `PUT /api/rule`：沿用规则编辑接口，算力规则要求带最新 `version`，不能改变编号、概念或引擎；保存后回到草稿。

演练结果固定携带 `dryRun=true`、`authorizesExecution=false`。过期定义版本拒绝，未知处理函数、实现版本或废止规则 R-09 不会执行。正式运行所需的 `rule_version` 快照与生效集合不在本次接口中创建。

## 迁移与协作

新增 V6，不修改已执行的 V1–V5。原始 V3 的五条规则仅在仍为草稿、版本 1、无执行引擎和指标关联，且名称、概念、类型、描述、严重度、负责人及原 JSON 均未变化时原位升级为定义版本 2；新增规则为版本 1。原始编号保留，不自动发布。

人工编辑或已发布的旧规则不覆盖；全局编号冲突不抢占。此时目录会提示不兼容，需要人工核对后补齐定义，不应直接清库重建。V6 不修改业务库、分配、持有、执行快照或 M-01 的概念与字段映射。

代码交付在 `feat/a800-compute` 的工作区，提交和推送前与同事核对迁移编号，避免另一项工作也使用 V6。

## 验证方式

单元测试：

```powershell
mvn '-Dtest=ComputeRuleEvaluatorTest,ComputeRuleServiceTest,ComputeProfileTest,ComputeDatabaseTest,ComputeGroupQueryTest' test
```

MySQL 集成测试沿用现有随机隔离库工具。设置 `COMPUTE_ONTOLOGY_MYSQL_URL`、`COMPUTE_MYSQL_USERNAME`、`COMPUTE_MYSQL_PASSWORD`，以及 `COMPUTE_MYSQL_SMOKE=true` 后运行：

```powershell
mvn '-Dtest=ComputeRuleMigrationTest,ComputeMysqlSmokeTest' test
```

前者验证 V5→V6、原 ID 与映射及持有记录保留、九条新增、五条原位升级、重复迁移无变化、人工修改和发布规则不被覆盖。后者使用随机隔离的两套库，验证真实 HTTP 演练、版本漂移拒绝、未认证访问拒绝、规则编辑版本递增，以及演练前后分配账本不变。测试结束清理自己生成的测试库，不能把未设置环境变量而跳过的测试计为通过。

前端检查：`pnpm build`。运行验收与实际数据库迁移版本以本次交付记录为准。

## 本次交付验证（2026-11-11）

27 项定向测试通过，前端与后端构建通过。共享平台库迁移到 V6，规则表已在本机忽略目录 `.local/backups/` 留存升级前备份。使用原有共享管理员实际登录并逐条调用 14 条样例演练，全部返回预期结果；旧规则版本被拒绝，未登录演练返回 401。

共享库中 M-01 的 18 个概念、138 项属性、30 条关系不变；规则执行快照和执行动作数量仍为零，测试创建的临时库均已清理。规则定义仍为草稿，没有自动发布或执行动作。

本机浏览器自动化宿主不可用，页面已通过 Vue/Vite 构建，但尚未完成视觉/点击验收。可在本机访问 `/ontology?tab=rule`，按“页面使用”一节手动核对。
