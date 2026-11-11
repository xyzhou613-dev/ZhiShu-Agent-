# N-01 算力申请接口

以 `compute` profile 启动后端。接口使用现有 `a800_compute` 表，支持 `ADMIN` 和 `EDITOR`。Demo 由调用者指定已有员工编号作为申请人。启动配置和数据库准备见 [算力版验收说明](A800-compute-acceptance.md)。

以下示例使用 PowerShell。按本地配置替换服务地址和管理员密码。

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$loginBody = @{ username = 'admin'; password = '<管理员密码>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$requests = "$baseUrl/api/compute/requests"
```

成功响应包含 `code=0`、`msg="ok"` 和 `data`。参数或业务条件不满足时，现有接口约定返回 HTTP 200，响应内 `code=500`，具体原因在 `msg`。缺少有效令牌返回 HTTP 401，角色无权限返回 HTTP 403。

## 提交申请

`POST /api/compute/requests` 接收 JSON。以下字段均必填。

| 字段 | 类型与校验 |
|---|---|
| `employeeId` | 正整数，引用已有员工 |
| `projectId` | 正整数，引用已有项目 |
| `taskTypeId` | 正整数，引用已有任务类型 |
| `clientRequestKey` | 同一员工下的客户端请求编号，去掉首尾空白后为 1 至 128 个字符 |
| `requestedGpuCount` | 1 至 32767 的整数。五卡及以上也可提交，由后续调度判定 |
| `requestedResourceSpec` | JSON 对象。可提供 `vramBytesPerGpu`、`memoryBytes`、`temporaryBytes`、`persistentBytes`，均为可存入有符号 64 位整数的非负字节值。可选 `gpuInterconnect` 为非空字符串 |
| `launchSpec` | JSON 对象，必含非空字符串 `imageRef`。可选 `commandArgs` 为字符串数组，`mounts` 为对象数组 |

下面的三个引用编号均为 `1`，分别对应样例员工 `DEMO-001`、项目 `DEMO` 和任务类型 `TRAINING`。发送申请只登记需求和启动规格，初始状态为 `SUBMITTED`。

```powershell
$body = @{
    employeeId = 1
    projectId = 1
    taskTypeId = 1
    clientRequestKey = [guid]::NewGuid().ToString()
    requestedGpuCount = 1
    requestedResourceSpec = @{ memoryBytes = 34359738368 }
    launchSpec = @{ imageRef = 'demo/train' }
} | ConvertTo-Json -Depth 8
$created = Invoke-RestMethod -Method Post -Uri $requests -Headers $headers -ContentType 'application/json' -Body $body
$id = $created.data.id
```

响应示例中的编号和时间由服务端生成。

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "id": 2,
    "employeeId": 1,
    "projectId": 1,
    "taskTypeId": 1,
    "clientRequestKey": "31e98a93-4f22-4690-a115-c9da765e87ed",
    "requestedGpuCount": 1,
    "requestedResourceSpec": { "memoryBytes": 34359738368 },
    "launchSpec": { "imageRef": "demo/train" },
    "status": "SUBMITTED",
    "submittedAt": "2026-11-11T01:20:00Z"
  }
}
```

常见校验错误包括 `员工不存在`、`项目不存在`、`任务类型不存在`、`requestedGpuCount 不能超过 32767`、`memoryBytes 必须是非负整数字节值` 和 `launchSpec.imageRef 必须是非空字符串`。

同一员工用同一个请求编号和同样的内容再次提交时，返回原申请编号及当前状态。JSON 对象字段顺序可以不同，数组顺序属于申请内容。同键修改卡数或其他申请内容会收到以下响应。

```json
{"code":500,"msg":"申请编号已用于不同的申请内容","data":null}
```

## 查询申请

- `GET /api/compute/requests` 返回 `data.list`、`data.total`、`data.pageNum`、`data.pageSize`。列表中的每项与提交接口的申请对象一致。按提交时间及编号倒序，`pageNum` 默认 1，`pageSize` 默认 20，最多 200。
- `GET /api/compute/requests/{id}` 返回 `data.request`、`data.jobs`、`data.currentJob`。作业按编号倒序。首次提交的申请详情中 `jobs` 为 `[]`，`currentJob` 为 `null`。已批准样例 `1` 包含作业 `1`。作业对象的字段见重试响应示例。

```powershell
(Invoke-RestMethod -Uri "${requests}?pageNum=1&pageSize=20" -Headers $headers).data
(Invoke-RestMethod -Uri "$requests/$id" -Headers $headers).data
(Invoke-RestMethod -Uri "$requests/1" -Headers $headers).data
```

以下为刚提交申请的详情响应字段示例。申请对象复用提交接口的 `data`。

```powershell
$detail = Invoke-RestMethod -Uri "$requests/$id" -Headers $headers
$detail.data.request.status  # SUBMITTED
$detail.data.jobs.Count      # 0
$detail.data.currentJob      # null
$page = Invoke-RestMethod -Uri "${requests}?pageNum=1&pageSize=1" -Headers $headers
$page.data.list[0].id        # 最新申请的编号
$page.data.pageNum          # 1
$page.data.pageSize         # 1
```

编号不存在时，业务错误为 `算力申请不存在`。

## 取消申请

`POST /api/compute/requests/{id}/cancel` 使用路径中的申请编号，无请求体。仅 `SUBMITTED` 且没有队列、作业或执行流程记录的申请可以取消。成功返回同一个申请对象，`data.status` 为 `CANCELLED`。重复取消返回当前申请。

```powershell
(Invoke-RestMethod -Method Post -Uri "$requests/$id/cancel" -Headers $headers).data.status
# CANCELLED
(Invoke-RestMethod -Method Post -Uri "$requests/1/cancel" -Headers $headers).msg
# 仅可取消尚未进入调度的已提交申请
```

已批准样例 `1` 或已有调度记录的申请返回 `仅可取消尚未进入调度的已提交申请`。编号不存在时返回 `算力申请不存在`。

## 重试失败作业

`POST /api/compute/requests/{id}/jobs/{jobId}/retry` 使用申请和失败作业编号，无请求体。首次重试须满足以下条件。

- 申请为 `APPROVED`。
- 作业属于该申请，是编号最新的作业，状态为 `FAILED`，已有结束时间。
- 该申请没有活跃作业，原作业没有执行流程记录，原作业没有 `PENDING` 或 `OCCUPIED` 分配。

成功复制原作业的任务类型、有效卡数和有效资源规格，新增 `PENDING` 作业并登记为当前作业。重复调用同一个失败作业的重试接口时，返回该申请的第一条后续作业记录。历史作业保留。

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "id": 3,
    "requestId": 2,
    "taskTypeId": 1,
    "effectiveGpuCount": 2,
    "effectiveResourceSpec": { "memoryBytes": 12345678 },
    "status": "PENDING",
    "createdAt": "2026-11-11T01:25:00Z",
    "startedAt": null,
    "endedAt": null
  }
}
```

| 条件 | 业务错误 |
|---|---|
| 找不到申请 | 算力申请不存在 |
| 作业不存在或不属于该申请 | 申请下不存在该作业 |
| 作业未结束或状态不是失败 | 仅可重试已结束的失败作业 |
| 申请尚未批准 | 仅已批准的申请可重试作业 |
| 活跃作业、执行流程或有效分配阻止重试 | 申请仍有关联的活跃作业、执行流程或有效分配 |

### 验证重试

原始样例作业 `1` 处于 `PENDING`。成功场景由集成测试在随机临时库创建一条已批准申请和已结束的失败作业，向同一路径并发发送两次请求，核对两次返回同一作业、详情显示一个当前作业、原失败作业仍在历史记录中。

按 [算力版验收说明](A800-compute-acceptance.md) 设置测试环境变量，在后端目录运行以下命令。

```powershell
mvn '-Dtest=ComputeMysqlSmokeTest#retriesAFailedJobOnceUnderConcurrentRequests+rejectsRetryWhenTheRequestOrSourceJobIsIneligible' test
```

N-01 保存申请和作业记录。审批、选卡、排队、预留和容器执行由后续接口与流程负责。
