# N-13 模拟节点与通知适配器

模拟器用来验证“发出操作、等待回执、核对结果”这条链路，不连接真实 GPU、容器或通知渠道。它接入 [N-12 动作管理](A800-compute-execution-action-api.md)，保存独立的模拟运行状态，所有观测及回执带 `simulation=true`、来源 `A800_SIMULATOR_V1`。

业务库 V13 新增 `compute_simulator_state`、`compute_simulator_scenario`、`compute_simulator_operation`，共 44 张业务表（不含 Flyway 历史）。平台库仍为 V9。现有持有、预留、申请、作业和流程记录不会被模拟操作改写。

## 开启与初始化

默认关闭。使用 `compute` profile，并显式配置：

```yaml
compute:
  runtime:
    mode: SIMULATION
    node-key: a800-demo
  simulator:
    enabled: true
```

示例配置也支持环境变量 `COMPUTE_SIMULATOR_ENABLED=true`。`GET /api/compute/runtime` 的 simulatorEnabled 表示开关状态。REAL 仍被拒绝启动，模拟器不能代替 N-16 真实采集及执行适配器。

开启后须由管理员调用初始化接口。首次初始化以当前未关闭持有区间生成演示物理状态：部署占用其持有组全部卡；作业按实际 OCCUPIED 分配的 GPU 成员导入；PENDING 预留不算实际占用。原样例导入三项运行部署、七卡占用、FLEX 单卡空闲。初始化依据明确记为 CURRENT_LEDGER_ONCE，代表演示起点，不是实机采集。

重复初始化返回原模拟状态。应用重启不重置状态、不刷新样例观测、不自动采集或执行。仅允许操作 `compute.runtime.node-key` 配置的节点。没有重置或清空接口，避免丢失操作去重记录。

## HTTP 接口与权限

登录及 Bearer Token 沿用 [申请接口](A800-compute-request-api.md)。未登录 401；角色不允许 403。业务异常沿用 HTTP 200、code=500、msg 为原因；成功 code=0、data 为下述结果。

| 方法与路径 | 权限 | 行为 |
|---|---|---|
| GET /api/compute/simulator | ADMIN / EDITOR | 查询模拟标记、节点、来源及支持动作 |
| POST /api/compute/simulator/nodes/{nodeKey}/initialize | ADMIN | 显式初始化；重复调用保留状态 |
| GET /api/compute/simulator/nodes/{nodeKey} | ADMIN / EDITOR | 查询模拟 GPU、部署和作业状态 |
| PUT /api/compute/simulator/nodes/{nodeKey}/state | ADMIN | 设置 GPU 健康、部署健康和在途请求数 |
| POST /api/compute/simulator/nodes/{nodeKey}/observations | ADMIN | 生成本次模拟观测，可注入采集失败 |
| PUT /api/compute/simulator/scenarios | ADMIN | 在操作首次接收前配置延迟、失败或超时 |
| GET /api/compute/simulator/operations?operationKey=... | ADMIN / EDITOR | 查询模拟端原操作、计数及回执 |

此处不提供任意操作执行入口。动作仍由后端流程通过 N-12 的 prepare、dispatch 接入，HTTP 只可按 N-12 规则核对和安全重试。N-11 尚未实现，所以原样例不会自动生成流程或开始停服、训练。HANDOVER、FINALIZE 属于业务事务，不交给模拟执行器冒充成功。

## 模拟状态与节点动作

状态返回 gpus、deployments、jobs 三个对象，按数据库编号作为键。GPU 保存 groupId、healthy、owner（如 DEPLOYMENT:2、JOB:1，空闲为 null）；运行对象保存 running、healthy、gpuIds 及 memoryBytes、temporaryBytes、persistentBytes。部署另有 acceptingTraffic、inFlightRequests、revisionId。不返回镜像凭据或实际命令。

管理员只能修改健康和在途请求数，不能直接填“已停止”“GPU 已释放”或改占用主体：

```json
{"gpus":[{"id":5,"healthy":false}],"deployments":[{"id":2,"healthy":true,"inFlightRequests":3}]}
```

id 必须为当前模拟状态已有的正整数；healthy 为布尔值；inFlightRequests 为 0～1000000 的整数。未知字段、非法类型及超过 64K 字符的请求拒绝，整个修改事务回滚。

| 动作 | 模拟行为 |
|---|---|
| NOTIFY | 记录一次模拟渠道送达；输入 recipients 为 1～100 个 employee:编号 / team:编号字符串，重复收件人去重；不发邮件或站外消息 |
| DRAIN | 停止接收新请求，保留当前在途数；不会擅自清零 |
| STOP | 须已摘流且在途数为零；停止运行并释放模拟 GPU |
| START_JOB | 须有 OCCUPIED 分配、未关闭持有、卡组当前持有指针一致且无待生效指针；使用卡数匹配，模拟 GPU 健康且可用后启动 |
| STOP_JOB | 停止该作业并释放模拟 GPU；不释放业务分配或持有 |
| RESTORE | 须先停止部署，按流程固定 restore_revision_id 恢复，不读取最新修订；GPU 健康且可用后启动，暂不接流量 |
| HEALTH_CHECK | 检查部署在运行、部署健康及使用卡健康；失败不能伪装为通过 |
| RESUME_TRAFFIC | 上述健康检查条件通过后接回流量 |

启动和恢复按字节检查模拟容量；容量不足仍允许通知、摘流和停服。运行实例计入内存使用；临时、持久存储占用在停服后保留，不假定删除容器就清理磁盘。模拟健康、停服和启动回执只说明模拟端结果，不能替代 N-11 的规则授权、N-08 的原子交接或 N-09 的收尾事务。

## 场景与原编号去重

先初始化，再按固定 operationKey 配置场景；可在 N-12 创建动作之后、首次发送之前配置：

```json
{"operationKey":"flow:42:stop","mode":"FAIL_THEN_SUCCESS","failAttempts":1,"delayMs":0}
```

| 字段 / mode | 含义 |
|---|---|
| operationKey | 1～128 个字母、数字或 `_.:/-` 字符，与 N-12 登记编号一致 |
| SUCCESS | 默认；条件满足后成功 |
| FAILURE | 确认失败，不产生模拟效果，不允许重试 |
| FAIL_THEN_SUCCESS | 前 failAttempts 次确认失败并允许重试，之后再检查实际条件；failAttempts 默认 1，范围 1～10 |
| TIMEOUT | 模拟回执丢失：动作可以已经成功，发送方仍得到 UNKNOWN，必须查询原编号确认 |
| delayMs | 0～300000 的整数，默认 0；保存 ready_at，未到时间返回 UNKNOWN / SIMULATED_DELAY |

延迟不占着线程睡眠。原操作已持久接收，到期后在核对原结果或采集观测时完成该操作；没有后台自动推进定时器，GET 状态不会偷偷执行动作。TIMEOUT 可与延迟组合，核对接口最终返回原操作真实模拟结果。

模拟端保存不可变命令及场景副本。重启、重复发送和重复核对复用该编号及回执；成功结果不能改写。网络重复发送不算新重试，只有 N-12 的发送尝试号增加，且上一结果明确允许重试时，才接收下一尝试。一次成功最多提交一次效果，不重复送达通知或重复占卡。失败条件修复后仍须按 N-12 安全重试入口处理，UNKNOWN 禁止直接重发。

操作查询字段沿用下划线命名：operation_key、action_id、node_id、status（ACCEPTED / SUCCEEDED / FAILED）、scenario_json、attempt_count、send_count、query_count、effect_count、ready_at、receipt、error_code、retry_allowed。ready_at 为 UTC；effect_count 为 0 或 1，表示是否已成功提交该操作；发送和查询计数也包含重复投递。ACCEPTED 对 N-12 返回 UNKNOWN。操作已接收后不能修改场景。操作去重记录和节点状态不允许删除；模拟端结果、事件和状态在同一事务提交。

常见结果码：SIMULATED_TIMEOUT、SIMULATED_DELAY、SIMULATED_FAILURE、SIMULATED_DRAIN_REQUIRED、SIMULATED_HANDOVER_REQUIRED、SIMULATED_GPU_BUSY、SIMULATED_UNHEALTHY、SIMULATED_CAPACITY_EXCEEDED。模拟端事务完全回滚且没有原操作记录时，查询可确认 SIMULATED_NOT_ACCEPTED，允许用原编号重试。

## 观测与规则来源

采集请求默认为 `{}`，生成八项 GPU 加一项节点水位观测，时间由服务端生成：

```json
{"validForSeconds":30,"failGpuIds":[5],"failNode":true}
```

validForSeconds 为 1～300 的整数，默认 30。失败 GPU 编号须为本节点不重复的正整数；failNode 为布尔值。失败记录 status=ERROR、values_json=null，并保留失败标记，不补零、不沿用成功回执。成功 GPU 记录 healthy、occupied；节点记录 memoryUsedBytes、temporaryUsedBytes、persistentUsedBytes。响应含 simulation、source、nodeKey、UTC observedAt / validUntil 和 observationIds。

一次采集及已到期模拟操作在同一事务提交，失败整体回滚。提交后发布 N-06 资源变化事件；队列是否自动重评仍取决于其开关。

模拟来源使用独立名称，不冒用 A800_DEMO_FIXTURE。若要让 N-03 / N-07 使用这些观测，须通过现有数据资产接口，将 GPU_OBSERVATION、NODE_WATERMARK 的 sourceKind 设为 SIMULATED、authoritativeSource 设为 A800_SIMULATOR_V1，登记合适的 maxAgeSeconds，再发布并激活规则集合，最后重新采集。发布方法见 [N-02](A800-compute-rule-evaluation-api.md)，来源管理见 [M-05](A800-compute-m05.md)。未完成时 SOURCE_MISMATCH 或来源时效缺失会明确阻断；延长 validForSeconds 也不会绕过生效规则的最大年龄。

## PowerShell

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$loginBody = @{ username = 'admin'; password = '<管理员密码>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.token)" }
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/compute/simulator/nodes/a800-demo/initialize" -Headers $headers -ContentType 'application/json' -Body '{}'
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/compute/simulator/nodes/a800-demo/observations" -Headers $headers -ContentType 'application/json' -Body '{}'
Invoke-RestMethod -Uri "$baseUrl/api/compute/simulator/nodes/a800-demo" -Headers $headers
# 固定编号由后端流程生成；配置必须发生在首次发送前。
$scenario = @{ operationKey = 'flow:42:stop'; mode = 'TIMEOUT'; delayMs = 0 } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$baseUrl/api/compute/simulator/scenarios" -Headers $headers -ContentType 'application/json' -Body $scenario
# 已产生操作后才能查询；此示例不创建或发送动作。
$operationKey = [uri]::EscapeDataString('flow:42:stop')
Invoke-RestMethod -Uri "$baseUrl/api/compute/simulator/operations?operationKey=$operationKey" -Headers $headers
```

## 验证范围

数据库测试覆盖 V12→V13、重复迁移、并发及重启去重、重复失败回执不触发重试、延迟与超时核对、停止前置条件、固定修订恢复、实际交接前拒绝启动、健康与流量控制、模拟观测失败及事务回滚、来源登记发布后才采信。开关测试覆盖默认关闭、显式开启及拒绝真实模式。HTTP 验证管理员控制、只读角色、匿名拒绝及经 N-12 核对丢失的停服回执；具体结果见 [验收记录](A800-compute-acceptance.md)。所有有效观测及动作只生成于随机隔离库，没有迁移或修改共享应用库。

按验收说明配置隔离测试库环境变量后，可在仓库根目录运行专项检查（COMPUTE_MYSQL_SMOKE=true 才会执行 HTTP 集成测试）：

```powershell
mvn -f bemodel-server/pom.xml '-Dtest=ComputeSimulatorDatabaseTest,ComputeSimulatorProfileTest,ComputeSimulatorHttpTest' test
```
