# N-12 执行动作管理与回执接口

本项解决“操作发出后，到底执行到哪一步、能不能重试”。先保存动作，再发送；超时不直接判失败，而是用原操作编号查询。动作和事件使用算力业务库同一 DataSource 的事务，执行器调用在事务之外。

启用 `compute` profile，业务库升级到最新版本（N-12 初始为 V12，N-13 后为 V13），平台元数据库仍为 V9。V12 扩展 `execution_action`，复用 `resource_event`，本身不新增业务表；V13 增加三张模拟器内部表后共 44 张业务表。旧动作、旧回执和幂等编号保留；旧记录没有受管输入及原执行器身份时，只能查询，不能自动发送或核对。

**本项没有注册生产执行器，也没有实现节点模拟器。** 目前默认数据没有执行流程，动作查询通常为空。N-11 负责创建及推进流程、检查实时规则和步骤前置条件；N-13 / 真实执行模块负责提供执行器。本项既不会发 SSH / Docker 命令，也不会把“已发指令”记为成功。

后续 N-13 已提供可显式开启的模拟节点与通知执行器，接入和限制见 [模拟器接口](A800-compute-simulator-api.md)。默认仍关闭，没有自动创建流程或执行动作，真实节点适配器仍未接入。

## HTTP 接口与权限

登录、Bearer Token 和 Result 格式沿用 [申请接口](A800-compute-request-api.md)。未登录 401；角色不允许 403，并写 M-16 拒绝审计；业务错误 HTTP 200、`code=500`、`msg` 为原因。正常结果 `code=0`。

| 方法与路径 | 权限 | 行为 |
|---|---|---|
| GET /api/compute/execution-actions | ADMIN / EDITOR | 分页查询；可选 workflowId、operationKey、status、pageNum、pageSize |
| GET /api/compute/execution-actions/{id} | ADMIN / EDITOR | 查询动作及按编号升序排列的历史事件 |
| POST /api/compute/execution-actions/{id}/reconcile | ADMIN / EDITOR | 查询原操作实际结果，不再次发送操作 |
| POST /api/compute/execution-actions/{id}/retry | ADMIN | 执行器确认失败且明确允许安全重试时，用原编号重试 |

POST 无业务请求字段，可发送 `{}`。不接受调用方填“成功”“已停服”“GPU 已释放”等回执。创建动作及首次发送只开放 Java 内部接入方法，没有公开 HTTP 入口。

分页按动作编号倒序，pageNum 默认 1，pageSize 默认 20、最大 200；小于 1 时分别归一为 1、20。workflowId 必须为正整数；operationKey 非空且不超过 128 字符；status 使用下表大写编码。原 `GET /api/compute/actions/results?workflowId=...&operationKey=...` 保持兼容，返回旧字段数组，新接口返回分页包装。

```json
{"code":0,"data":{"list":[],"total":0,"pageNum":1,"pageSize":20}}
```

## 动作状态与重试

| status | 含义与下一步 |
|---|---|
| PENDING | 已持久化，尚未发送；等待内部流程调用 dispatch |
| RUNNING | 已提交发送或查询的领取记录；租约有效时并发调用返回现状，不重复领取 |
| UNKNOWN | 超时、异常或不合格回执，无法确定实际结果；只能先 reconcile |
| SUCCEEDED | 执行器明确确认成功；重复调用返回原记录 |
| FAILED | 执行器明确确认失败；仅 retryAllowed=true 时可重试 |
| COMPENSATED | 兼容已有补偿状态；本项不提供将动作直接改成已补偿的接口 |

动作超时默认 `compute.actions.timeout-seconds=30`，范围 1～300 秒；领取租约在此基础上加 5 秒，用于防止正常调用被并发领取。适配器必须遵守调用超时。进程崩溃或回执写入失败后可能保留 RUNNING，租约过期后由 reconcile 领取并查询，不能直接重发。没有后台自动发送或自动重试任务。

attemptCount 只统计实际发送的领取次数，reconcileCount 单独统计查询次数；它们不等于远端确认的执行次数。UNKNOWN 不写 finishedAt，确认成功或失败才写 UTC 结束时间。异常统一使用 EXECUTOR_RESULT_UNKNOWN，不保存异常消息中的凭据或命令。回执缺失、格式错误或超长会记为 UNKNOWN / INVALID_EXECUTOR_RECEIPT。

重试复用原 action.id、operationKey、input、目标及 executorKey。原执行器不可用时拒绝更换执行器。流程必须仍为 READY / RUNNING，且步骤、row_version 和 decision_id 与登记时一致，才允许首次发送或重试。流程已推进后仍可查询旧动作实际结果，但不能重发旧步骤。最终规则授权和流程推进并发保护仍须 N-11 接入。

## 响应字段与事件

列表、核对和重试返回动作对象；详情返回 `{"action":{...},"events":[...]}`。

| 字段 | 说明 |
|---|---|
| id / workflowId / decisionId | 动作、流程和原批准决策编号 |
| actionType / targetKind / targetId | 动作类型及固定目标 |
| operationKey | 固定幂等编号；同键内容不同报冲突 |
| status / attemptCount / reconcileCount | 当前结果、发送次数和查询次数 |
| executorKey / retryAllowed | 已绑定执行器及确认失败后的安全重试标记 |
| leaseUntil / startedAt / finishedAt | UTC 领取截止、首次开始及结果确认时间；未发生时为空 |
| input / receipt / errorCode | 固定输入、最近回执和原因码 |
| createdBy / createdAt / updatedAt | 登记人和 UTC 登记、更新时间 |
| workflowStep / workflowVersion | 登记时固定的流程步骤、行版本；旧记录可以为空 |

事件字段为 `id`、`event_type`、`occurred_at`、`payload`。事件包括 ACTION_PREPARED、ACTION_SEND_STARTED、ACTION_QUERY_STARTED、ACTION_RETRY_STARTED、ACTION_EXECUTION_RESULT、ACTION_QUERY_RESULT 和 ACTION_STALE_REPLY。payload 保存该次尝试或核对的状态、次数、回执及领取编号；领取还记录调用人。重试不会覆盖前一次事件。动作身份、输入及已绑定执行器有数据库不可变保护，本项来源的事件只能追加。

每次登记、领取或结果落库，都和对应事件在同一事务提交；事件写入失败则状态更新回滚。领取先锁流程，再锁动作；领取提交后才调用执行器。结果写入校验领取编号，旧调用迟到时仅追加 ACTION_STALE_REPLY，不覆盖后来查询确认的结果。

input、receipt 和历史 payload 可能包含主机、参数或日志，只给 ADMIN / EDITOR；不进入 Agent 只读投影。权限目录增加 ACTION_RECONCILE，人工重试沿用仅 ADMIN 的 ACTION_RETRY。

## 后端接入约定

`ComputeActionService.prepare(Intent)` 独立提交动作意图及事件；输入为 workflowId、actionType、targetKind、targetId、operationKey、input。input 必须是 JSON 对象，序列化后最多 65536 字符；operationKey 为 1～128 个字母、数字或 `_.:/-` 字符。建议使用流程固定生成的小写编号；现有数据库唯一键可能将大小写变体视为冲突，不能通过改变大小写生成另一操作。JSON 属性顺序及 Java int / long 节点表示的差异不影响重复内容识别。

| 动作类型 | targetKind | 目标检查 |
|---|---|---|
| START_JOB / STOP_JOB / FINALIZE | JOB | 等于流程 job_id |
| HANDOVER | ALLOCATION | 分配属于流程作业和卡组 |
| NOTIFY / DRAIN / STOP / RESTORE / HEALTH_CHECK / RESUME_TRAFFIC | DEPLOYMENT | 部署属于流程卡组 |

创建时校验流程存在、状态可用、决策属于该申请且为 APPROVED，固定流程步骤与版本。该检查不替代 R-05 等实时规则。调用方应先完成授权和步骤选择事务，再 prepare；随后在事务外调用 dispatch。禁止在已经开启的事务内调用 prepare、dispatch、reconcile 或 retry，避免远端调用持锁及失败事务内回查。

适配器实现公共接口 `ComputeActionExecutor`，通过 Spring Bean 注册；key 必须稳定、唯一、长度 1～64，字符限字母、数字及 `_.:-`。新动作必须恰好有一个适配器支持其类型，否则拒绝发送，动作保持 PENDING。绑定后只认原 key。

- execute(Command, Duration)：按固定 operationKey 幂等执行。
- query(Command, Duration)：只查询该编号原结果，绝不能重新发送动作。
- Reply：state 为 SUCCEEDED / FAILED / UNKNOWN，receipt 为最多 65536 字符的 JSON 对象；可选 errorCode 限 1～64 个字母、数字或 `_.:-` 字符。仅 FAILED 可通过 retryAllowed=true 允许重试。

适配器必须在自己的持久存储中跨进程、跨重启按编号去重，并核验回执属于同一操作；确认失败必须是权威终态。只有重新发送相同编号不会重复产生已有副作用时，才允许安全重试。模拟与真实适配器必须使用不同且固定的配置身份；N-12 不注册测试执行器到正式应用，也不改变 M-17 的 REAL 模式禁用策略。

## PowerShell 示例

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$loginBody = @{ username = 'admin'; password = '<管理员密码>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$page = Invoke-RestMethod -Uri "$baseUrl/api/compute/execution-actions?status=UNKNOWN&pageNum=1&pageSize=20" -Headers $headers
$page.data | ConvertTo-Json -Depth 20
# 只有已经由流程创建的实际动作才有编号；空列表时不执行后续请求。
if ($page.data.list.Count -gt 0) {
    $actionId = $page.data.list[0].id
    Invoke-RestMethod -Uri "$baseUrl/api/compute/execution-actions/$actionId" -Headers $headers
    $checked = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/compute/execution-actions/$actionId/reconcile" -Headers $headers -ContentType 'application/json' -Body '{}'
    $checked | ConvertTo-Json -Depth 20
    if ($checked.code -eq 0 -and $checked.data.status -eq 'FAILED' -and $checked.data.retryAllowed) {
        Invoke-RestMethod -Method Post -Uri "$baseUrl/api/compute/execution-actions/$actionId/retry" -Headers $headers -ContentType 'application/json' -Body '{}'
    }
}
```

常见业务错误：“未配置唯一匹配的动作执行器，拒绝发送”“原动作执行器不可用，拒绝更换执行器重发”“仅执行器确认失败且允许安全重试的动作可重试；未知结果请先核对”“流程步骤、版本或决策已变化，禁止重发旧动作”“执行动作不存在”。执行器调用超时返回正常 Result 中的 UNKNOWN 动作，不伪装成确认失败。

## 验证

`ComputeActionDatabaseTest` 使用随机隔离 MySQL 库及仅在测试源码中的执行器，覆盖 V11→V12、旧回执保留、重复迁移、同键并发登记、内容冲突、目标校验、无执行器拒绝发送、事务外调用、并发发送去重、超时后重启核对、安全重试、流程版本变化拒绝重发、过期领取、迟到回执保护、状态与事件整体回滚及敏感异常消息不落库。`ComputeActionControllerTest` 验证分页、Result 和角色；完整 HTTP 及空库初始化结果见 [验收记录](A800-compute-acceptance.md)。

按验收说明设置隔离库环境变量后，可以在仓库根目录一次运行以下检查：

```powershell
mvn -f bemodel-server/pom.xml '-Dtest=ComputeActionDatabaseTest,ComputeActionControllerTest,ComputeAccessPolicyTest,ComputeAccessInterceptorTest,ComputeNormGuardsTest,ComputeAgentViewTest,ComputeMysqlSmokeTest,ComputeOntologyMigrationTest#initializesConceptsAndAttributesFromAnEmptySchema' test
```
