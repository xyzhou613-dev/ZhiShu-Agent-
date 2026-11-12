# N-07 事务预留与提交决策接口

在 `compute` profile 启用，业务库升级到最新版本（N-07 初始为 V11，N-12 后为 V12），平台元数据库仍为 V9。V11 为正式决策补充规则集合和定义版本，为检查补充输入事实，新增 `compute_reservation_receipt`。旧决策与发布快照保留，不补造历史审批。

## 鉴权与接口

先按 [N-01](A800-compute-request-api.md) 登录取得 Bearer Token，并按 [N-02](A800-compute-rule-evaluation-api.md) 发布、激活完整且兼容的规则集合。以下接口限 ADMIN / EDITOR；未登录 401，无权限 403。业务异常沿用 HTTP 200、`code=500`、`msg` 原因；正常结果 `code=0`、`data` 为下述对象。

| 方法与路径 | 输入与行为 |
|---|---|
| POST /api/compute/reservations | JSON 仅接受 `nodeKey`、正整数 `requestId`；检查队首后尝试事务预留 |
| GET /api/compute/reservations | 可选 `requestId`，按回执编号倒序分页；`pageNum` 默认 1，`pageSize` 默认 20、最大 200 |
| GET /api/compute/reservations/{id} | 按回执编号查询回执、正式决策和逐条检查 |

先通过 [N-06](A800-compute-queue-api.md) 将申请加入目标节点队列。规格、目标组、优先级、暂停对象、规则版本和调用人均由服务端取得，不能在预留请求中指定。取消或不可调度申请不能新建预留。已有失败或结束作业的申请，需要按 N-01 重试得到新的 PENDING 作业后重新入队。

```json
{"nodeKey":"a800-demo","requestId":2}
```

## 成功、等待和重复调用

POST 的 `data` 字段：

| 字段 | 含义 |
|---|---|
| outcome | RESERVED：本次成功；ALREADY_RESERVED：已有有效分配；HISTORICAL_RESERVATION：当前作业的分配已结束；WAITING：本次条件不满足 |
| reasonCode | 新成功为 PASS；重放为 REPLAYED_RESERVATION；旧无回执分配为 LEGACY_ALLOCATION；等待原因见下表 |
| allocationId / allocationStatus | 分配编号和当前状态，等待时为空 |
| receipt | 正式回执；等待或旧无回执分配时为空 |
| projectionRunId | 本次容量投影编号；尚未投影或重复调用时可以为空 |
| queue | 当前队列记录；旧无队列分配可以为空 |
| authorizesExecution | 始终 false；预留成功也不授权启动容器 |

回执包含 `id`、`requestId`、`jobId`、`allocationId`、`groupId`、`nodeId`、`allocationStatus`、`ruleSetId`、`snapshotId`、`projectionRunId`、`admissionDecisionId`、`submissionDecisionId`、`restoreRevisionId`、UTC 时间 `asOf` 和 `actor`。其中 allocationStatus 查询当前分配状态，其余字段用于解释当时预留。没有暂停部署时 restoreRevisionId 为空。

重复提交同一申请时，当前作业已有分配就返回原分配及原回执，不创建第二份。已取消或释放的历史分配返回 HISTORICAL_RESERVATION，不重新激活。原样例申请 1 已有 PENDING 分配，返回 ALREADY_RESERVED / LEGACY_ALLOCATION，receipt 为空。

| 等待原因 | 含义 |
|---|---|
| QUEUE_NO_TARGET | 当前无法选择目标组，例如五卡及以上申请 |
| QUEUE_NOT_HEAD | 不是该目标组队首 |
| QUEUE_CONFLICT | 目标已有待生效预留，或对应指针未清理 |
| GROUP_HELD | 目标由作业分配实际持有，不能再预留 |
| GROUP_LEDGER_MISMATCH | 实际持有、分配及卡组指针不一致，须先核对账本 |
| MISSING_RESTORE_REVISION | 暂停对象没有可固定的恢复修订 |
| UNKNOWN_GPU_OCCUPANCY | 空闲组观测到无法由账本解释的实际占用 |
| QUEUE_STALE_EVIDENCE | 观测过期或不可用 |
| NEEDS_PENDING_JOB | 当前作业不能再分配；队列同步退出为 CANCELLED |

容量、显存、互联等检查直接保留 [N-05](A800-compute-capacity-projection-api.md) 的原因码，例如 QUEUE_NO_CAPACITY。WAITING 通常保留队列等待状态并更新原因，不新增作业、正式决策或分配；已完成的快照和投影保留供解释。NEEDS_PENDING_JOB 是退出队列的特殊情况。无生效规则、规格非法等属于业务异常，不是资源等待。

## 事务和证据

节点、该节点全部卡组、相关申请按固定顺序加锁；同一组只允许一个有效待生效预留，跨组也串行检查节点容量。事务中重评目标与队首，固定生效规则集合，重新采集服务端证据并投影容量；成功时统一写入作业及 active_job、准入决策、PENDING 分配与 GPU 成员、卡组 pending 指针、提交决策和回执，同时将申请置为 APPROVED、队列置为 RESERVED。已有合格 PENDING 作业会复用。

准入保存 R-01、R-11 和 R-10；提交保存容量投影检查，以及队首 R-12、事务回执 R-07、恢复检查 R-13 和证据链 R-10。无需暂停时 R-13 为 NOT_APPLICABLE。逐条检查固定执行版本、定义版本、事实、结果和结构化输出，关联本次快照全部证据作为运行依赖集合。数据库拒绝关联其他快照的证据。正式准入和提交通过 previous_decision_id 相连，同类前序通过 supersedes_decision_id 保留。

写入前及事务返回前都按服务端当前时间再次检查观测有效性；提交前过期报“证据在提交前过期，请重评后重试”，整个事务回滚。其他写入失败也整体回滚。成功提交后发布资源变化事件；自动重评是否启用按 N-06 配置，轮询本身不会自动调用预留接口。

若 B 组由模型 B 实际持有且满足暂停条件，可以为训练写入 PENDING，同时固定实际持有修订用于后续恢复；模型 B 的 hold_period 和 current_hold_id 保持原状。N-07 不停止模型、不切换持有、不将分配置为 OCCUPIED，也不创建容器执行流程；这些留给后续执行条目。

详情返回 `receipt`、`decisions`、`checks`。后两项字段沿用数据库命名，例如 `previous_decision_id`、`rule_code`、`rule_version_id`、`definition_version`、`reason_code`；`facts` 和 `output` 为 JSON 对象。快照全文可通过 `GET /api/compute/evidence-snapshots/{snapshotId}` 查询。分页返回沿用 `list/total/pageNum/pageSize`。

## PowerShell 示例

使用实际新提交申请编号。固定教学观测已过期，且没有默认节点水位观测，原数据直接调用通常返回等待；成功预留必须由真实采集链路提供有效观测。不要为演示修改共享观测时间。

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$loginBody = @{ username = 'admin'; password = '<管理员密码>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$requestId = 2 # 替换为新提交的申请编号
$body = @{ nodeKey = 'a800-demo'; requestId = $requestId } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/compute/queue" -Headers $headers -ContentType 'application/json' -Body $body
$reserved = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/compute/reservations" -Headers $headers -ContentType 'application/json' -Body $body
$reserved.data | ConvertTo-Json -Depth 20
Invoke-RestMethod -Uri "$baseUrl/api/compute/reservations?requestId=$requestId&pageNum=1&pageSize=20" -Headers $headers
if ($reserved.data.receipt) {
    $receiptId = $reserved.data.receipt.id
    Invoke-RestMethod -Uri "$baseUrl/api/compute/reservations/$receiptId" -Headers $headers
}
# 重复调用：成功预留后应返回相同 allocationId 和 receipt.id
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/compute/reservations" -Headers $headers -ContentType 'application/json' -Body $body
```

## 验证

隔离 MySQL 测试从 V10 升级 V11，覆盖陈旧证据、非队首、重复与并发预留、取消竞争、跨组节点容量竞争、原部署实际持有不变、恢复版本固定、跨快照引用拒绝、写入失败全量回滚、历史版本保留和重复迁移。HTTP 回归检查登录、角色、实际预留、重复结果、正式决策和分页详情。测试生成的有效观测及较长有效期只写随机临时库；未更新实际应用库。
