# N-04 目标组选择接口

本次范围为“目标选择＋等待结果”。服务端复用 N-03 现场快照和生效集合中的 R-04，保存每次选择、当时依据及等待原因。实际入队和仲裁留给 N-06，容量判定、预留和执行按后续条目接入。

## 规则与结果含义

| 有效卡数 | 目标策略 |
|---|---|
| 1 | FLEX 的 GPU 健康、观测有效、未占用，且账本无持有或预留时选 FLEX；否则选 B |
| 2 | 只选 B |
| 3、4 | 只选 A |
| ≥5 | 无目标，返回 WAITING / QUEUE_NO_TARGET |

B 不可用时不会改选 A，A 不可用时也不会借用其他组，C 不参与选择。当前申请有 active_job 时使用该作业的 effective_gpu_count，specBasis=JOB；没有时使用 requested_gpu_count，specBasis=REQUEST。作业及有效规格也会固定在账本证据中，调用方不能指定卡数或作业。

R-04 的 PASS 仅表示选中了符合策略的目标。selectionStatus 解释目标的当前状态：

| selectionStatus | 含义 |
|---|---|
| TARGET_SELECTED | 当前观测和账本没有直接阻塞；仍需 R-02 / R-03 / R-14 判定方案可行性 |
| WAITING | 没有目标，或目标有预留、账本不一致、观测不可用、健康卡数不足、未登记的物理占用或作业持有等阻塞 |
| PREEMPTION_CHECK_REQUIRED | 目标由常驻部署持有，且没有其他直接阻塞；仍需 R-13 等后续检查，不能据此确认可抢占 |

持有与预留分别记录。模型 B 仍持有 B 组且已有 PENDING 预留时，目标仍是 B，状态为 WAITING，不会把预留当成新作业已经持有。

例如三卡申请选 A 组，四张卡中有三张健康、一张故障时，不因未使用卡故障就直接判为等待。后续 R-14 仍须确定并校验实际使用的三张卡；只有两张健康时，本次返回 WAITING，但目标仍保持 A。

output.blockingReasons 保存 GROUP_HELD、GROUP_RESERVED、GROUP_LEDGER_MISMATCH、GPU_EVIDENCE_UNAVAILABLE、GPU_UNHEALTHY、GPU_OCCUPIED 等原因。常驻部署占用 GPU 属于抢占评估的依据，不代表停服或抢占已经获准。

queuePersisted=false、authorizesExecution=false 始终返回。WAITING 是本次检查结果，不表示已写入队列表；TARGET_SELECTED 和 PREEMPTION_CHECK_REQUIRED 也不会批准申请、修改作业或创建分配。

## 启用和鉴权

使用 compute profile，业务库迁移到最新版本（N-04 初始为 V8，N-12 后为 V12）。ADMIN / EDITOR 可以调用全部三个接口；VIEWER / REVIEWER 无权读取或提交。

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$loginBody = @{ username = 'admin'; password = '<管理员密码>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$targetUrl = "$baseUrl/api/compute/target-selections"
```

必须先发布并激活完整规则集合，步骤见 [N-02 接口文档](A800-compute-rule-evaluation-api.md)。目标选择使用该集合固定的 R-04 执行版本、来源配置及卡组 ID。节点标识、组 ID 和 4/2/1/1 布局不匹配时拒绝运行，不能用同名组临时代替。

## 三个接口

| 方法与路径 | 行为 |
|---|---|
| POST /api/compute/target-selections | 接收 nodeKey 和 requestId，现场采集并保存选择结果 |
| GET /api/compute/target-selections | 按编号倒序分页，可用 requestId 筛选 |
| GET /api/compute/target-selections/{id} | 查询选择、R-04 事实、输出、证据引用及完整快照 |

nodeKey 是最多 64 字符的非空字符串；requestId 是正整数，关联记录必须存在。不限制申请状态，查询已批准或已有作业的申请也不会改变其状态。

不接受 asOf、snapshotId、facts、evidence，也不允许指定 jobId、gpuCount、targetGroupId、targetGroupCode、targetGroup、flex、ruleSetId。时间和选择依据由服务端生成。每次提交创建新快照和新选择记录。

列表参数 pageNum 默认 1，小于 1 按 1；pageSize 默认 20，小于 1 按 20，最大 200。requestId 筛选参数如果提供，必须大于 0；不存在的正整数返回空列表。响应沿用 Result 和 PageResult，列表数据为 data.list/total/pageNum/pageSize。

## PowerShell 示例

用现有申请 1 选择目标：

```powershell
$body = @{ nodeKey = 'a800-demo'; requestId = 1 } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri $targetUrl -Headers $headers -ContentType 'application/json' -Body $body
if ($created.code -ne 0) { throw $created.msg }
$created.data | ConvertTo-Json -Depth 40
$selectionId = $created.data.selection.id
Invoke-RestMethod -Uri "$targetUrl/$selectionId" -Headers $headers
Invoke-RestMethod -Uri "${targetUrl}?requestId=1&pageNum=1&pageSize=20" -Headers $headers
```

原样例当前作业的有效卡数为 2，因此选 B。B 有实际持有及待生效预留，且固定教学观测已经过期，预期为 WAITING。以下为关键字段节选，编号及版本以实际响应为准：

```json
{
  "selection": {
    "id": 1,
    "requestId": 1,
    "jobId": 1,
    "gpuCount": 2,
    "specBasis": "JOB",
    "targetGroupId": 2,
    "targetGroupCode": "B",
    "selectionStatus": "WAITING",
    "ruleResult": "PASS",
    "reasonCode": "PASS",
    "ruleSetId": 1,
    "ruleVersionId": 4,
    "ruleVersion": 1,
    "definitionVersion": 7,
    "snapshotId": 1,
    "queuePersisted": false,
    "authorizesExecution": false
  },
  "facts": {"gpuCount": 2},
  "output": {
    "targetGroup": "B",
    "selectionStatus": "WAITING",
    "blockingReasons": ["GROUP_HELD","GROUP_RESERVED","GPU_EVIDENCE_UNAVAILABLE"],
    "requiresFeasibilityChecks": ["R-02","R-03","R-14"]
  }
}
```

提交和详情的完整 data 为 `{selection,facts,output,evidenceIds,evidenceSnapshot}`。selection 还包含 nodeId、asOf、actor。ruleVersion 是业务库不可变执行版本号，definitionVersion 是发布时的平台定义版本。详情不会重新计算旧结果。

五卡申请无目标的示例，先按 N-01 登记，再选择：

```powershell
$requestBody = @{
    employeeId = 1; projectId = 1; taskTypeId = 1
    clientRequestKey = 'n04-five-' + [guid]::NewGuid().ToString('N')
    requestedGpuCount = 5
    requestedResourceSpec = @{ memoryBytes = 34359738368; vramBytesPerGpu = 85899345920 }
    launchSpec = @{ imageRef = 'demo/train' }
} | ConvertTo-Json -Depth 8
$request = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/compute/requests" -Headers $headers -ContentType 'application/json' -Body $requestBody
if ($request.code -ne 0) { throw $request.msg }
$body = @{ nodeKey = 'a800-demo'; requestId = $request.data.id } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri $targetUrl -Headers $headers -ContentType 'application/json' -Body $body
```

返回 targetGroupId / targetGroupCode 为空，ruleResult=FAIL、reasonCode=QUEUE_NO_TARGET、selectionStatus=WAITING。申请仍为 SUBMITTED，不会新增作业或队列记录。

## 快照、事务和业务边界

单次选择在算力业务库同一 REPEATABLE_READ 事务中固定生效集合与服务端 asOf，采集 N-03 快照，再调用该集合的 R-04。结果保存到 target_group_selection，引用保存到 target_group_selection_evidence。快照、结果或引用任一写入失败，整体回滚；外键限制引用来自同一快照、同一节点和申请，以及同一规则集合。

证据包含账本以及本次检查过的 FLEX / 目标组 GPU 观测。旧记录保留当时的卡数、目标、规则版本、时间和原因，新发布或新观测不会覆盖旧记录。卡数为 2～4 时，FLEX 状态不影响固定目标；节点水位等其他阶段的观测问题不替代 R-04 的选组逻辑，仍须由后续规则检查。

这不是 N-02 整个 PLANNING 阶段的自动执行接口，R-02 / R-03 / R-14 的服务端方案事实仍由后续容量与可行性功能接入。现有六阶段 caller facts 接口及 N-03 服务端 ADMISSION 模式保持原有行为。快照格式见 [N-03 文档](A800-compute-evidence-api.md)。

## 错误

成功响应 code=0、msg="ok"；业务错误为 HTTP 200、code=500、msg 为原因。未登录返回 401，无权限返回 403。

| 条件 | msg |
|---|---|
| 输入缺失或类型错误 | 快照需要有效的 nodeKey 和正整数 requestId |
| 传入旧时间、快照或自定义证据 | 快照时间和证据由服务端生成，不能由调用方传入 |
| 指定目标、卡数或作业 | 目标、卡数和当前作业由服务端确定，不能由调用方指定 |
| 节点 / 申请不存在 | 节点不存在 / 算力申请不存在 |
| 与发布布局不一致 | 节点卡组配置与生效集合不一致，请核对后重新发布 |
| 详情不存在 | 目标组选择记录不存在 |
| 非法筛选参数 | requestId 必须是正整数 |
| 没有生效集合 | 尚未激活算力规则集合，请先发布并同步规则 |

例如查询不存在的编号，响应为：

```json
{"code":500,"msg":"目标组选择记录不存在","data":null}
```

## 验证

按 [验收说明](A800-compute-acceptance.md) 设置隔离 MySQL 变量，在 bemodel-server 目录运行：

```powershell
mvn '-Dtest=ComputeTargetSelectorTest,ComputeTargetSelectionDatabaseTest,ComputeEvidenceDatabaseTest,ComputeMysqlSmokeTest' test
```

单元测试覆盖卡数表、FLEX 各类阻塞、布局校验、有效作业规格、部分未使用卡故障和抢占待核对状态。数据库测试验证 V7→V8、有效观测下选择 FLEX、来源不符时回到 B、三张健康卡的 A 组目标、五卡无目标、历史与版本保留、跨快照引用保护和引用写入失败整体回滚。HTTP 验证登录、三个接口、权限、分页、输入限制及现有功能回归。有效观测只在隔离测试库构造，不会刷新共享样例。
