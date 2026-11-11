# N-06 队列与串行仲裁

compute profile 下启用，业务库 V10 扩展原 compute_queue：绑定节点、最新目标选择记录、重评时间和下一次建议检查时间。原有分组队列回填所属节点；无法识别节点的旧无目标记录保留为空，须明确入队绑定节点。不会重写其提交时间。

## 接口与权限

全部接口限 ADMIN / EDITOR，复用 Bearer Token、Result 和 PageResult。未登录 401，无权限 403；业务错误 HTTP 200、code=500、msg 为原因。

| 方法与路径 | 行为 |
|---|---|
| POST /api/compute/queue | `{nodeKey,requestId}` 入队或重新检查原记录 |
| GET /api/compute/queue | nodeKey 默认 a800-demo，status 默认 WAITING；支持 pageNum/pageSize |
| GET /api/compute/queue/{requestId} | 查看该申请的队列记录 |
| POST /api/compute/queue/reevaluate | `{nodeKey}` 串行重新检查节点待调度队列 |

nodeKey / requestId 校验沿用 N-03。不能传入 priority、submittedAt、targetGroupId、gpuCount 或 jobId 改变排队顺序。只有 SUBMITTED / APPROVED 且无有效分配的待调度申请可入队；已有节点绑定时不能移到其他节点。尚未产生作业时可入队；已有作业时须是未发生过分配的当前 PENDING 作业，否则应先按 N-01 完成失败作业重试。重评发现不合格作业会将队列置为 CANCELLED / NEEDS_PENDING_JOB，避免阻挡队首。

排序固定为 priority 降序、原申请 submitted_at 升序、requestId 升序。同时间以编号兜底。训练等级取生效集合的 trainingPriority，其他类型取登记等级；重评不会将时间改成“现在”使申请掉到队尾。单节点所有队列写入先锁节点，再按 ID 锁卡组和申请，确保同组及跨组容量入口串行。

返回字段包括 requestId、nodeId、targetGroupId/Code、priority、submittedAt、status、reasonCode、lastSelectionId、lastEvaluatedAt、nextEvaluateAt、position、head、authorizesExecution=false。position/head 只对 WAITING 且有目标的记录有意义；五卡无目标保留 WAITING，position 为空、head=false。队首仅表示排序第一，不表示证据和容量已经通过。

status 支持 WAITING / RESERVED / CANCELLED。RESERVED 由有效分配或 [N-07 事务预留](A800-compute-reservation-api.md) 更新；本接口不创建分配。reasonCode 是最近检查的等待原因，完整依据见 lastSelectionId 对应的 [N-04 记录](A800-compute-target-selection-api.md)。

列表按队列顺序分页，pageNum 默认 1，pageSize 默认 20、最大 200；小于 1 的页码或页大小归一化为 1 / 20。

## 取消与重评

尚无作业、流程和分配的 SUBMITTED 排队申请，可调用原 `POST /api/compute/requests/{id}/cancel`：在同一事务中取消申请和队列，重复调用返回原申请。原节点未知的旧队列记录维持取消保护，先核对或通过入队接口绑定节点。已批准或已产生作业的取消仍留给 N-10，不会从队列接口释放资源。

每次重评重新调用 N-04 服务端快照选组；FLEX 从不可用变可用时，单卡申请可以从 B 转到 FLEX，反之回到 B。同组排序仍按原始申请时间。取消及非活跃申请不再参与重评。

支持手动重评、定时重评和资源变化事件。自动重评默认关闭；需要时配置：

```yaml
compute:
  queue:
    recheck-enabled: true
    recheck-delay-ms: 30000
  rules:
    node-key: a800-demo
```

周期默认 30 秒；nextEvaluateAt 为默认策略的建议检查时间，手动或事件可提前重评。资源写入模块应在事务提交后发布内部 ResourceChanged(nodeKey) 事件，或调用重评接口。自动功能开启后，事件立即触发检查；失败记录日志，下一轮轮询重试。N-07 成功提交预留后会发布该事件。轮询和事件仅重评，不自动创建预留。未接入采集器时，直接改数据库不能自动发布事件，定时轮询仍会重新读取。

## PowerShell

先按 [N-02 文档](A800-compute-rule-evaluation-api.md) 登录并激活规则；使用实际待调度申请编号：

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$loginBody = @{ username = 'admin'; password = '<管理员密码>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$requestId = 2 # 替换为 N-01 提交的申请编号；原样例 1 已有有效分配，不重复入队
$body = @{ nodeKey = 'a800-demo'; requestId = $requestId } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/compute/queue" -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "$baseUrl/api/compute/queue?nodeKey=a800-demo&pageNum=1&pageSize=20" -Headers $headers
$refreshBody = @{ nodeKey = 'a800-demo' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/compute/queue/reevaluate" -Headers $headers -ContentType 'application/json' -Body $refreshBody
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/compute/requests/$requestId/cancel" -Headers $headers -ContentType 'application/json' -Body '{}'
```

典型业务错误为“申请已有有效分配，不重复入队”“申请已绑定其他节点队列”“队列等级、顺序和目标由服务端确定”“队列记录不存在”。教学观测过期会影响选组，不会自动改新采集时间。

## 验证

隔离 MySQL 验证 V9→V10、等级排序、同时间编号兜底、并发重复入队、原提交时间保留、FLEX 重选、排队取消、写入失败整体回滚及再次迁移无变化。调度单测覆盖默认关闭、事件与轮询触发、失败后重试。两项 HTTP 检查覆盖新队列接口及原有取消保护，合计 23 项定向检查通过、无跳过。未迁移现有应用库，未启用共享环境自动重评。
