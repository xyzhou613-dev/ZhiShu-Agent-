# N-02 生效规则运行接口

以 `compute` profile 启动后端，业务库迁移到 V5。以下三个接口允许 `ADMIN` 和 `EDITOR` 调用。

| 方法与路径 | 功能 |
|---|---|
| `POST /api/compute/rule-evaluations` | 固定当前生效集合，执行一个阶段，保存输入和全部结果 |
| `GET /api/compute/rule-evaluations` | 分页查询运行摘要 |
| `GET /api/compute/rule-evaluations/{id}` | 查询输入、来源版本和逐条结果 |

事实由调用方提供，记录的 `inputSource` 为 `CALLER`，`authorizesExecution` 为 `false`。这些记录用于查看规则计算结果。申请的正式决策和服务端证据由后续流程接入。

## 登录

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$loginBody = @{ username = 'admin'; password = '<管理员密码>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$runsUrl = "$baseUrl/api/compute/rule-evaluations"
```

成功响应使用 `code=0`、`msg="ok"` 和 `data`。业务错误沿用 HTTP 200 和响应内 `code=500`，原因位于 `msg`。未登录或令牌无效返回 HTTP 401，角色无权限返回 HTTP 403。

## 发布并激活规则

规则定义、审核和发布沿用 M-04。管理员先在规则页面核对 14 条规则，使其完成 DRAFT → REVIEW → PUBLISHED 流转，再调用预检查和整体发布。已有生效集合时可以直接执行下一节的运行示例。

```powershell
$preflight = Invoke-RestMethod -Uri "$baseUrl/api/compute/releases/preflight" -Headers $headers
$preflight.data
# ready=false 时，按 errors 修正规则或配置，再重新检查。

$publishBody = @{ changeSummary = '发布经核对的算力规则'; force = 'true' } | ConvertTo-Json
$release = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/release/publish" -Headers $headers -ContentType 'application/json' -Body $publishBody
if ($release.code -ne 0) { throw $release.msg }
$releaseId = $release.data.id
$history = Invoke-RestMethod -Uri "$baseUrl/api/compute/releases" -Headers $headers
$history.data.active
```

示例的 `force=true` 用于确认现有本体检查警告，阻断错误仍会使发布失败。发布由 ADMIN/REVIEWER 审核，通过平台提交后的同步激活业务库快照。运行接口本身要求 ADMIN/EDITOR。

若发布历史中的同步状态为 FAILED，可使用原发布编号重试，然后再次检查生效集合。

```powershell
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/compute/releases/$releaseId/sync" -Headers $headers
(Invoke-RestMethod -Uri "$baseUrl/api/compute/releases" -Headers $headers).data.active
```

未激活集合时，运行请求返回 `尚未激活算力规则集合，请先发布并同步规则`。发布、冲突处理和同步细节见 [M-04 说明](A800-compute-m04.md)。

## 六个运行阶段

| stage | 执行的规则 |
|---|---|
| `ADMISSION` | R-01、R-11 |
| `PLANNING` | R-02、R-03、R-04、R-14 |
| `SUBMISSION` | R-07、R-12、R-13 |
| `EXECUTION` | R-05 |
| `CLOSURE` | R-06、R-15 |
| `GLOBAL` | R-08、R-10 |

`POST` 请求包含字符串 `stage` 和对象 `facts`。阶段名称区分大小写，服务端按上表执行该阶段的全部规则。每条规则的事实以规则编号为键。

- `facts` 总长度最多 65536 个字符，按 JSON 序列化文本计算。
- 阶段未知、facts 不是对象或包含其他阶段的规则编号时，整个请求被拒绝。
- 某条规则的事实缺失、类型错误或条件不满足时，保存判定器实际返回的结果。缺少 R-01 规格通常返回 FAIL/DENY_SPEC，其他缺失事实通常返回 ERROR/INVALID_FACTS。
- 同样内容每调用一次，都会生成一条新的运行记录。写入失败时，主记录和所有明细一起回滚。

下面的准入请求可直接提交。时间为历史教学样例，供核对条件判断。R-11 按 facts 中的 `asOf` 判断证据时效，服务端另外记录本次的 `evaluatedAt`。

```powershell
$body = @{
    stage = 'ADMISSION'
    facts = @{
        'R-01' = @{
            gpuCount = 1
            taskType = 'TRAIN'
            resourceSpec = @{
                vramBytesPerGpu = 85899345920
                memoryBytes = 34359738368
                temporaryBytes = 0
                persistentBytes = 0
            }
        }
        'R-11' = @{
            asOf = '2026-11-06T10:00:10Z'
            evidence = @(@{
                observedAt = '2026-11-06T10:00:00Z'
                validUntil = '2026-11-06T10:00:30Z'
            })
        }
    }
} | ConvertTo-Json -Depth 12
$created = Invoke-RestMethod -Method Post -Uri $runsUrl -Headers $headers -ContentType 'application/json' -Body $body
$created | ConvertTo-Json -Depth 16
$runId = $created.data.run.id
```

其他阶段的输入契约沿用 M-02。可以从生效集合读取每条定义自带的 `sampleFacts`，组成请求，避免把后续编辑中的定义当成本次集合。

```powershell
$active = (Invoke-RestMethod -Uri "$baseUrl/api/compute/releases" -Headers $headers).data.active
$setId = $active.id
if (-not $setId) { throw '请先发布并激活规则集合' }
$snapshot = (Invoke-RestMethod -Uri "$baseUrl/api/compute/releases/sets/$setId" -Headers $headers).data
$stage = 'PLANNING'
$facts = @{}
foreach ($rule in $snapshot.rules) {
    if ($rule.stage -eq $stage) {
        $definition = $rule.definition | ConvertFrom-Json
        $facts[$rule.rule_code] = $definition.sampleFacts
    }
}
$body = @{ stage = $stage; facts = $facts } | ConvertTo-Json -Depth 40
Invoke-RestMethod -Method Post -Uri $runsUrl -Headers $headers -ContentType 'application/json' -Body $body
```

## 运行结果和版本

提交和详情查询均返回 `data.run`、`data.facts`、`data.checks`。以下为完整准入响应示例，编号、版本和时间以实际返回为准。

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "run": {
      "id": 1,
      "stage": "ADMISSION",
      "ruleSetId": 1,
      "sourceReleaseId": 1,
      "sourceVersionTag": "v1.0",
      "evaluatorVersion": "a800-m02-v1",
      "formulaVersion": "a800-formulas-v1",
      "actor": "admin",
      "evaluatedAt": "2026-11-11T03:00:00Z",
      "inputSource": "CALLER",
      "overallResult": "PASS",
      "authorizesExecution": false
    },
    "facts": {
      "R-01": {
        "gpuCount": 1,
        "taskType": "TRAIN",
        "resourceSpec": {
          "vramBytesPerGpu": 85899345920,
          "memoryBytes": 34359738368,
          "temporaryBytes": 0,
          "persistentBytes": 0
        }
      },
      "R-11": {
        "asOf": "2026-11-06T10:00:10Z",
        "evidence": [
          {
            "observedAt": "2026-11-06T10:00:00Z",
            "validUntil": "2026-11-06T10:00:30Z"
          }
        ]
      }
    },
    "checks": [
      {
        "ruleVersionId": 1,
        "ruleCode": "R-01",
        "ruleVersion": 1,
        "definitionVersion": 2,
        "result": "PASS",
        "reasonCode": "PASS",
        "output": {
          "gpuCount": 1
        }
      },
      {
        "ruleVersionId": 10,
        "ruleCode": "R-11",
        "ruleVersion": 1,
        "definitionVersion": 3,
        "result": "PASS",
        "reasonCode": "PASS",
        "output": {
          "asOf": "2026-11-06T10:00:10Z",
          "maxAgeSeconds": 30
        }
      }
    ]
  }
}
```

- `run.ruleSetId` 固定本次使用的生效集合，来源发布为 `sourceReleaseId/sourceVersionTag`。一轮运行中的全部规则来自这个集合。
- `checks[].ruleVersionId` 引用业务库的不可变执行快照，`ruleVersion` 是该规则的执行版本号。
- `checks[].definitionVersion` 是发布时 BeModel 规则定义的版本。它与执行版本分别编号。
- `overallResult` 优先汇总 ERROR，再 FAIL，再 PASS，全部不适用时为 NOT_APPLICABLE。逐条结果、原因码和输出完整保留。
- R-06 的结果为 PASS 时，还须读取 `output.outcome`，其值为 HANDOFF、RESTORE 或 WAIT_RESOURCE。

后续发布会生成新集合和新执行版本。旧运行记录继续关联原集合，详情保留原始输入与结果。

## 列表和详情

```powershell
$page = Invoke-RestMethod -Uri "${runsUrl}?pageNum=1&pageSize=20" -Headers $headers
$page.data
$detail = Invoke-RestMethod -Uri "$runsUrl/$runId" -Headers $headers
$detail.data | ConvertTo-Json -Depth 40
```

列表返回 `data.list/total/pageNum/pageSize`，按运行编号倒序。`list` 每项为上面的 run 摘要。pageNum 默认 1，小于 1 时按 1；pageSize 默认 20，小于 1 时按 20，最大 200。详情返回本次 run、facts 和 checks。

| 业务条件 | 返回信息 |
|---|---|
| 请求不是对象，或 stage 不是字符串 | 运行请求必须包含字符串 stage 和对象 facts |
| 未知阶段 | 未知规则运行阶段 |
| facts 结构错误或过长 | facts 必须是 JSON 对象，且不超过 64K 字符 |
| facts 包含无关编号 | facts 包含不属于该阶段的规则编号 |
| 集合缺失、不完整或实现版本不兼容 | 对应快照错误，整次请求拒绝 |
| 记录不存在 | 规则运行记录不存在 |

例如提交 `{"stage":"UNKNOWN","facts":{}}` 时，HTTP 200 响应如下，且不会新增运行记录：

```json
{"code":500,"msg":"未知规则运行阶段","data":null}
```

## 验证方式

按 [算力版验收说明](A800-compute-acceptance.md) 设置隔离 MySQL 测试环境变量，在 `bemodel-server` 目录运行：

```powershell
mvn '-Dtest=ComputeRuleRunnerTest,ComputeRuleEvaluationDatabaseTest,ComputeMysqlSmokeTest' test
```

测试覆盖六阶段的 14 条规则、快照参数与版本固定、历史查询、异常事实、写入中途失败整体回滚、分页和权限。测试发布和运行记录在随机测试库中创建。
