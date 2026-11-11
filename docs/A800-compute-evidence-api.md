# N-03 证据快照与服务端准入运行

本次实现“快照＋准入运行”。复用 `evidence_snapshot` 和 `evidence` 保存固定依据，准入结果仍是 N-02 的独立运行记录。正式申请决策、批准、选卡和执行由后续条目接入，返回的 `authorizesExecution` 始终为 `false`。

## 启用和鉴权

以 `compute` profile 启动后端，业务库迁移到 V7。V6 增加快照上下文、封存及不可变保护；V7 将运行、逐条检查和证据关联起来。保留 V1～V5，旧 CALLER 运行记录不改写。

以下接口限 ADMIN / EDITOR，登录后使用 Bearer Token。成功响应为 `code=0,msg="ok",data=...`；业务错误沿用 HTTP 200、`code=500`；未登录返回 401，无权限返回 403。

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$loginBody = @{ username = 'admin'; password = '<管理员密码>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$snapshotUrl = "$baseUrl/api/compute/evidence-snapshots"
$runUrl = "$baseUrl/api/compute/rule-evaluations"
```

采集前必须已有完整的生效规则集合，发布及激活方法见 [N-02 接口文档](A800-compute-rule-evaluation-api.md)。使用发布集合固定的 R-11 参数和 GPU_OBSERVATION / NODE_WATERMARK 来源登记，不读取编辑中的最新配置。

## 接口

| 方法与路径 | 请求 / 行为 |
|---|---|
| `POST /api/compute/evidence-snapshots` | `{nodeKey,requestId}`，生成并封存一次快照 |
| `GET /api/compute/evidence-snapshots` | 分页查询已封存的 N-03 快照 |
| `GET /api/compute/evidence-snapshots/{id}` | 返回固定上下文、问题清单和证据值 |
| `POST /api/compute/rule-evaluations` | 新增 `{stage:"ADMISSION",nodeKey,requestId}` 模式，现场采集新快照并执行准入规则 |

`nodeKey` 必须是非空字符串，最多 64 字符，节点必须存在。`requestId` 必须是正整数，申请必须存在。不限定申请状态；对已批准申请再次检查也不会修改其状态。

快照时间、来源和证据由服务端确定，不能传入 `asOf`、`facts`、`evidence` 或 `snapshotId`。服务端准入模式只支持 ADMISSION，不能混入 caller facts，也不能选择旧快照。其余五个阶段继续沿用 N-02 的 `stage + facts` 模式。

每次调用创建新快照；每次准入运行创建新快照及新运行。单独采集的快照用于查看现场，不会在下一次运行中自动复用。

## 保存哪些依据

一次采集固定一个服务端 UTC `asOf`，在算力业务库同一 REPEATABLE_READ 事务中读取、写入并封存：

| 证据 | 保存内容 |
|---|---|
| 平台账本 | 申请规格、任务类型及状态；节点容量；卡组指针；开放持有；PENDING / OCCUPIED 分配及其资源需求；分配用卡；持有部署的修订与资源基线；队列记录 |
| GPU 观测 | 源观测编号、来源、节点内 GPU 身份、healthy、occupied、原 observedAt / validUntil、状态及可用性原因 |
| 节点水位 | 源观测编号、来源、节点身份、memoryUsedBytes / temporaryUsedBytes / persistentUsedBytes、原时间及状态 |
| 规则和来源配置 | 固定 ruleSetId，保存 14 条执行版本及定义版本引用，冻结两类观测的来源、登记版本和时效配置 |

账本来源标记为 PLATFORM_LEDGER，时间表示本次事务读取时间，其有效范围是本次 `asOf`，不是监控采集时间。`ledgerIsHistorical=false`：快照记录本次看到的当前账本，不能据此查询创建快照以前任意时刻的完整账本。

实际持有和待生效预留分开保存。现有样例中，B 组的开放持有指向模型 B，分配 1 仍为 PENDING；这不表示训练已经取得实际持有。

观测只保存选定字段，不复制整条原始报文、启动命令、挂载或账号日志。接口用于维护人员查看依据，后续 Agent 工具仍须遵守 M-05 的字段过滤契约。

## 有效性与缺失处理

- 对每个已登记 GPU 及节点，选择 `observed_at <= asOf` 的最新观测；同时间按编号倒序。最新一条失败时保留失败，不回退到较早的成功记录。
- 按同一个 `asOf` 检查 `observedAt <= asOf <= validUntil` 和最大年龄。最大年龄使用已发布 R-11 的 maxAgeSeconds；资产另有上限时取两者最小值。资产上限为空时使用 R-11 上限，不修改资产登记。
- 来源必须匹配发布时的 authoritative_source，来源阶段须为 SIMULATED / PLATFORM / CONNECTED。GPU 使用 GPU UUID 核对，节点使用 nodeKey 核对。
- GPU 的 healthy / occupied 必须是 JSON 布尔值。节点三个水位必须是非负、可用 BIGINT 表示的整数字节值。字符串 `"true"` 和字符串字节值不会被当成合格数据。
- 缺失观测只登记 MISSING_OBSERVATION，不伪造 evidence、采集时间或零值。失败、过期和类型错误会保留实际记录及原因，快照的 manifest.usable 为 false。
- 观测中的 healthy=false 是已采集的健康事实；是否满足资源方案由后续对应规则检查。R-11 不替代资源健康和容量判定。

封存后的快照不能改时间、改值、删除或追加证据。源记录后来更新、规则后来发布、账本后来变化，旧快照仍保留原引用和原值。数据库触发器提供保护，所有快照数据写入失败时整体回滚。

manifest.usable 只表示本次必需观测的来源、身份、字段类型和时效可用，不代表资源可分配、容量足够或申请已批准。

当前固定教学样例观测在 `2026-11-06T10:00:30Z` 到期，且没有节点水位观测。在此之后采集，看到 STALE_EVIDENCE / MISSING_OBSERVATION 是预期结果，不会自动刷新这些时间或假装接通真实监控。本次不新增采集上报接口，真实采集和完整数据治理仍由 N-16 / M-09 接入。

## PowerShell 示例

使用现有申请 1 采集并查询：

```powershell
$captureBody = @{ nodeKey = 'a800-demo'; requestId = 1 } | ConvertTo-Json
$captured = Invoke-RestMethod -Method Post -Uri $snapshotUrl -Headers $headers -ContentType 'application/json' -Body $captureBody
if ($captured.code -ne 0) { throw $captured.msg }
$snapshotId = $captured.data.snapshot.id
$captured.data | ConvertTo-Json -Depth 40
Invoke-RestMethod -Uri "$snapshotUrl/$snapshotId" -Headers $headers
Invoke-RestMethod -Uri "${snapshotUrl}?pageNum=1&pageSize=20" -Headers $headers
```

采集和详情的 `data` 为 `{snapshot,evidence,authorizesExecution}`：

- `snapshot`：id、asOf、ruleSetId、nodeId、requestId、actor、sealed、manifest。
- `manifest`：inputSource、ledgerIsHistorical、maxAgeSeconds、dataPolicies、ruleVersions、issues、usable。
- `evidence[]`：id、source、sourceRecordRef、observationId、observedAt、validUntil、values。非观测的账本 evidence 的 observationId 为空。

列表为 `data.list/total/pageNum/pageSize`，按编号倒序。pageNum 默认 1，小于 1 按 1；pageSize 默认 20，小于 1 按 20，最大 200。只列出 N-03 已完整封存的快照，旧占位快照不会冒充已采集依据。

发起服务端准入运行：

```powershell
$admissionBody = @{ stage = 'ADMISSION'; nodeKey = 'a800-demo'; requestId = 1 } | ConvertTo-Json
$evaluated = Invoke-RestMethod -Method Post -Uri $runUrl -Headers $headers -ContentType 'application/json' -Body $admissionBody
if ($evaluated.code -ne 0) { throw $evaluated.msg }
$evaluated.data | ConvertTo-Json -Depth 40
$runId = $evaluated.data.run.id
Invoke-RestMethod -Uri "$runUrl/$runId" -Headers $headers
```

服务端从刚采集的申请生成 R-01 规格，从观测原时间生成 R-11 事实。申请省略临时或持久存储需求时，按 N-01 的可选需求默认值 0 处理；这不用于填补观测缺失。缺少显存要求等必填规则字段时，保留 R-01 的实际失败结果。现有申请 1 缺少 vramBytesPerGpu，运行不会擅自从显卡规格推断申请要求。

以下是现有样例准入响应的关键字段节选，编号以实际返回为准：

```json
{
  "run": {
    "id": 12,
    "stage": "ADMISSION",
    "ruleSetId": 1,
    "inputSource": "SERVER_SNAPSHOT",
    "snapshotId": 8,
    "asOf": "2026-11-11T06:00:00Z",
    "evaluatedAt": "2026-11-11T06:00:00Z",
    "overallResult": "FAIL",
    "authorizesExecution": false
  },
  "checks": [
    {"ruleCode":"R-01","result":"FAIL","reasonCode":"DENY_SPEC","evidenceIds":[71]},
    {"ruleCode":"R-11","result":"FAIL","reasonCode":"QUEUE_STALE_EVIDENCE","evidenceIds":[72,73,74,75,76,77,78,79]}
  ]
}
```

完整结果仍包含 N-02 的原有字段和 facts，另有 `evidenceSnapshot` 返回完整快照。R-01 的 evidenceIds 指向账本证据，R-11 指向实际采集到的观测证据。缺失观测的说明位于同一快照 manifest.issues；不能因为没有对应 evidence 行就忽略缺失。

运行与快照使用同一个 ruleSetId 和 asOf。快照、运行、全部检查及引用在同一业务库事务中提交，引用写入失败也全部回滚；复合外键阻止跨快照混用。N-02 的 CALLER 运行新增字段 snapshotId / asOf / evidenceSnapshot 为空，逐条 evidenceIds 为空列表。

R-11 对时间之外的数据可用性问题也返回 QUEUE_STALE_EVIDENCE，output.issues 说明是缺失、采集失败、来源不符、身份不符、类型错误还是过期。其他阶段的规则输入不在本次自动生成范围。

## 业务错误与边界

| 情况 | msg |
|---|---|
| nodeKey / requestId 结构非法 | 快照需要有效的 nodeKey 和正整数 requestId |
| 手工指定快照依据 | 快照时间和证据由服务端生成，不能由调用方传入 |
| 节点或申请不存在 | 节点不存在 / 算力申请不存在 |
| 快照不存在或未封存 | 证据快照不存在 |
| 非 ADMISSION 的服务端输入 | 服务端证据目前仅支持 ADMISSION 阶段 |
| 运行指定旧 snapshotId / asOf | 运行不能指定旧快照或服务端判定时间 |
| 无生效集合或配置缺项 | 对应规则集合或来源时效配置错误，整次请求拒绝 |

例如尝试在运行请求中指定旧快照，返回 HTTP 200：

```json
{"code":500,"msg":"运行不能指定旧快照或服务端判定时间","data":null}
```

观测不可用是可保存的检查结果，不等同于请求结构错误。即使运行 PASS，也不改变申请状态、不创建作业、不排队、不预留、不启动容器。正式决策表 decision / rule_check / rule_check_evidence 仍留给后续流程；本次使用 rule_evaluation_check_evidence 关联独立运行。

## 验证

按 [验收说明](A800-compute-acceptance.md) 设置隔离 MySQL 环境变量，在 bemodel-server 下运行：

```powershell
mvn '-Dtest=ComputeEvidenceAdmissionTest,ComputeEvidenceDatabaseTest,ComputeEvidenceEvaluationDatabaseTest,ComputeRuleEvaluationDatabaseTest,ComputeMysqlSmokeTest' test
```

数据库用例创建随机临时库，使用固定历史时间验证快照边界，仅在隔离库补入节点观测和测试用的新鲜时间以验证 PASS。HTTP 用例使用原样例检查失败、查询、权限和服务端输入限制。测试不会刷新共享库观测。
