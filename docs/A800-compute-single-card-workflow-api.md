# N-11、N-08 / N-10：FLEX 单卡流程

本文记录 V14 的**空闲 FLEX 单卡**路径：原子交接 → 模拟启动 → 停止确认 → 回收。V15 已在同一推进器上增加 [B 组两卡抢占及原部署恢复](A800-compute-preemption-recovery-api.md)，接口、租约和动作机制继续复用。下一队首让位仍未完整实现；不会把旧样例申请 1 的 B 组预留直接激活。

业务库新增 V14，扩展现有 workflow_run，不增加业务表，总数仍为 44。managed 标记区分新流程与旧历史；同一作业只有一个受管流程。旧流程和旧执行动作保持原样。

## 准备

以 compute profile 启动，显式开启模拟器并初始化节点，登记 A800_SIMULATOR_V1 观测来源、发布完整规则集合后采集新观测。详见 [N-13](A800-compute-simulator-api.md)。先通过 [N-01](A800-compute-request-api.md) 提交单卡申请，再经 [N-06](A800-compute-queue-api.md)、[N-07](A800-compute-reservation-api.md) 入队和预留。创建流程需要 N-07 返回的 receipt.id，不能用 allocationId 替代。

## 接口与权限

沿用登录、Bearer Token、Result 和 PageResult。业务错误为 HTTP 200、code=500；匿名 401；角色不允许 403。

| 接口 | 权限 | 行为 |
|---|---|---|
| POST /api/compute/workflows | ADMIN / EDITOR | 请求 reservationReceiptId，创建或返回该作业原流程 |
| GET /api/compute/workflows | 四种角色 | 分页查询受管流程，按编号倒序 |
| GET /api/compute/workflows/{id} | 四种角色 | 查询步骤、目标、等待原因和动作摘要 |
| POST /api/compute/workflows/{id}/advance | ADMIN / EDITOR | 领取并推进或核对一步 |
| POST /api/compute/workflows/{id}/stop | ADMIN / EDITOR | 请求停止，确认退出后回收 |
| POST /api/compute/workflows/{id}/cancel | ADMIN / EDITOR | 请求取消，按当前步骤安全回收 |

```json
{"reservationReceiptId":2}
```

stop、cancel、advance 无业务请求字段，发送 `{}`。分页 pageNum 默认 1，pageSize 默认 20、最大 200。详情沿用数据库下划线字段，如 request_id、job_id、step、status、desired_state、row_version、next_run_at、last_error、end_reason，并附 actions 数组（id、action_type、workflow_step、status、attempt_count、error_code）。不返回领取令牌。动作输入及回执明细仍受 N-12 的 ADMIN / EDITOR 限制；人工安全重试仅 ADMIN。

## 推进与恢复

| 步骤 | 检查与行为 |
|---|---|
| HANDOVER | 锁节点、组、申请、作业、流程；检查原预留、空闲组、GPU 成员和指针；采集服务端新证据，重新投影容量、适配和健康，要求规则集合仍与原批准相同；同事务激活 OCCUPIED、建立持有并清除待生效指针 |
| START_JOB | 登记固定编号动作并通过 N-12 发送；只有成功且 running=true 才进入运行状态 |
| RUNNING | 保留持有，等待 stop / cancel；反复推进不会重复启动 |
| STOP_JOB | 使用固定停止动作；UNKNOWN 只查询原结果；确认 running=false 且 gpuReleased=true 才进入回收 |
| RELEASE | 同事务关闭本作业持有、清除当前持有指针、释放分配、更新作业和申请，退出队列 |
| DONE | 完成；重复调用不再改变账本 |

领取租约为 90 秒，并用令牌防止旧工作者覆盖新步骤。执行器调用在领取事务提交之后进行。动作编号为 workflow:流程编号:步骤:行版本。流程步骤未变化时，重启和重复推进继续使用原动作；UNKNOWN 不产生新操作编号。行版本只在步骤转换时递增，避免领取或轮询破坏原动作的版本绑定。

取消在 HANDOVER 前可直接撤销 PENDING；交接后必须取得停止确认。取消与未知启动相遇时，先核对原启动，再停止，不会跳过未知动作直接回收。启动确认失败后也进入 STOP_JOB，完成退出检查后才释放，作业标为 FAILED，申请保留 APPROVED；但 N-01 仍要求原作业没有执行流程记录，当前受管流程的失败作业不能直接通过该接口重新训练。历史流程安全重试仍待后续补齐，不把保留 APPROVED 等同于已经支持重试。正常停止标为 STOPPED；取消标为 CANCELLED。失败停止不会自动重发，管理员可在 N-12 允许安全重试时处理。

交接证据不通过、规则变更或数据库写入失败时，交接事务整体回滚；last_error 保留原因，资源不会部分激活。运行及停止期间没有退出确认时，资源保持占用。部署恢复责任为空的 FLEX 直通路径不伪造通知、摘流、原部署停服回执。

## 轮询配置（M-17 补充）

```yaml
compute:
  workflow:
    enabled: true
    poll-delay-ms: 5000
```

默认关闭，可用 COMPUTE_WORKFLOW_ENABLED 开启。开启后检查 next_run_at，每批最多 50 个流程，逐个尝试领取，单项失败不阻断其他项；关闭时可手动推进。runtime 接口返回 workflowEnabled。重启不会清空步骤或重发已确认动作；未过期领取不会被抢走，过期后按原记录核对。

## M-15 工作台

登录后进入“算力工作台”（/compute）。菜单仅在算力服务可用时出现。ADMIN / EDITOR 可提交申请、选择记录、入队尝试预留、创建流程、推进、停止及取消；可查看申请下的作业历史并重试失败作业。管理员另可初始化模拟器、采集观测和安全重试动作。VIEWER / REVIEWER 只展示流程进度，不请求无权限的申请或原始回执接口，也没有写按钮。后端仍独立校验权限。

页面显示待生效预留、步骤、目标、动作结果、等待及异常。停止按钮表示请求退出，FLEX 是否回收以 DONE 及账本为准。FLEX 不自动采集或刷新过期观测；需保证观测来源、时效和生效规则相符。V15 的 B 分支在模拟器开启时逐步采集新观测，并展示原部署、固定修订及持续恢复责任，详见上方扩展说明。

## 调用示例

先按 N-01 登录，取得 $headers 和 N-07 receipt.id：

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$body = @{ reservationReceiptId = 2 } | ConvertTo-Json # 替换为实际编号
$flow = Invoke-RestMethod -Uri "$baseUrl/api/compute/workflows" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
$workflowId = $flow.data.id
Invoke-RestMethod -Uri "$baseUrl/api/compute/workflows/$workflowId/advance" -Method Post -Headers $headers -ContentType 'application/json' -Body '{}'
Invoke-RestMethod -Uri "$baseUrl/api/compute/workflows/$workflowId" -Headers $headers
Invoke-RestMethod -Uri "$baseUrl/api/compute/workflows/$workflowId/stop" -Method Post -Headers $headers -ContentType 'application/json' -Body '{}'
# 开启轮询后等待 DONE；未开启时继续按步骤 advance。
```

## 验证

使用随机隔离 MySQL 8 库验证并发领取、重复创建、交接写入回滚、过期领取恢复、取消、启动失败及丢失回执后重启核对。HTTP 用例走实际鉴权与 N-07→流程→回收接口。浏览器验收可显式设置 COMPUTE_BROWSER_DEMO=true 运行 ComputeWorkflowHttpTest，它会临时保留隔离服务供页面验证，完成标记后清理；普通测试不会等待浏览器。未配置数据库环境时，跳过不能计为通过。

浏览器验收模式将临时后端地址写入 `$env:TEMP\workflow-browser-ready.txt`，可将 Vite 的 VITE_API_TARGET 指向该地址。完成后创建 `$env:TEMP\workflow-browser-done.txt`，测试即结束并清理数据库，最长等待 8 分钟。隔离账号仅由测试类创建，不能当成正式环境登录信息。常规回归直接设置测试库环境变量与 COMPUTE_MYSQL_SMOKE=true，运行：

```powershell
mvn -f bemodel-server/pom.xml '-Dtest=ComputeWorkflowDatabaseTest,ComputeWorkflowSchedulerTest,ComputeWorkflowHttpTest' test
```
