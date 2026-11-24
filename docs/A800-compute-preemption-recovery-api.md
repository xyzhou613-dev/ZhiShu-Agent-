# B 组两卡抢占与原部署恢复（模拟）

业务基线为《A800 算力本体评审稿 v0.7》，基础表沿用数据库 v0.2。本次扩展已有 N-11 推进器、N-07 正式预留、N-12 动作和 N-13 持久模拟器，支持 B 组两卡训练抢占常驻部署，退出后按原修订恢复。空闲 FLEX 单卡仍走原路径。

这不是完整 N-09、N-14、N-15：只接通恢复和等待路径；存在 B 组待仲裁候选时明确阻断，不能用“没有实现让位”作为直接恢复的理由。没有真实 GPU、容器、消息平台调用。

## 与原文档、样例的差异

- 原单卡文档排除 B 组；现在增加下述分支，接口仍为 `/api/compute/workflows`。原 N-07 的 PENDING 仍只代表预留，不能等同实际持有。
- 原 RESTORE 投影因缺少每卡显存、互联需求而返回 `UNKNOWN_RESTORE_GPU_REQUIREMENTS`。现在仅在显式引用新的恢复需求登记时才进行完整检查；没有登记的旧调用仍返回未知。
- 样例模型 B 已配置 `preemptible=true`，实际开放持有指向 `deployment_revision=2`。该修订有内存、临时盘和持久盘需求，但缺少恢复 GPU 需求及 harness 责任。V15 不补造这些历史字段，必须由负责人为未来执行显式登记。登记中的 NONE 表示**没有互联要求**，不表示已探测出组互联能力。NVLINK / PCIE 在当前硬件能力未登记时明确阻断。
- 样例申请 1 的旧分配没有新的 N-07 正式回执，不能冒充可执行回执或自动抢占。共享库中的旧预留、观测和规则不在本次开发中修改。隔离测试单独清理自己的教学预留后创建新申请。
- V15 在 v0.2 基础表上增加 `deployment_recovery_spec`、`compute_restore_reservation`，并扩展现有 workflow_run；最新业务表数为 46。不是重新生成 v0.2 表，也不重写历史流程、修订、发布快照。
- 旧单卡说明曾将“启动失败后保留 APPROVED”描述为可直接 N-01 重试，与源码不符：N-01 仍要求原作业无执行流程记录。因此本次失败训练会先完成退出和恢复，不自动生成后续训练；已有流程的失败作业重试限制保持，原说明已修正。N-12 的管理员安全重试针对原执行动作，两者不能混淆。

## 恢复依据与接口

所有接口需 Bearer Token，沿用 Result：成功 `code=0`，业务拒绝 HTTP 200 / `code=500`，匿名 401，角色拒绝 403。

| 接口 | 权限 | 用途 |
|---|---|---|
| GET /api/compute/deployments | ADMIN / EDITOR | 查看部署、可抢占标记、原卡组、实际持有修订、当前配置修订、最新恢复需求登记 |
| POST /api/compute/deployments/{id}/recovery-specs | ADMIN / EDITOR | 为该部署已有修订追加未来恢复需求与 harness 责任 |

登记体示例（仅为隔离演示，实际数值和团队必须经过确认）：

```json
{"revisionId":2,"vramBytesPerGpu":1073741824,"gpuInterconnect":"NONE","harnessTeamId":1,"declaration":"隔离演示中，负责人确认每卡需求为 1 GiB，DEMO 团队承担 harness 责任"}
```

revisionId、vramBytesPerGpu、harnessTeamId 均为正的 long 整数；修订须属于部署，团队须存在。gpuInterconnect 为 NONE / NVLINK / PCIE；declaration 为非空、最多 512 字符的说明。内存和两类磁盘仍读取该修订的字节值。响应 `data` 是新登记记录，沿用数据库字段名，包含 id、revision_id、vram_bytes_per_gpu、gpu_interconnect、harness_team_id、declaration、declared_by、created_at；时间按 UTC 解释。

创建流程时，在资源事务中核对 N-07 回执中的恢复修订是否仍是实际持有修订、是否仍可抢占、是否为 B 组两卡 TRAINING，再固定原 hold、修订和最新显式登记。通知对象由部署负责人、负责人团队、项目关联团队及显式 harness 团队推导、去重并固定。无依据时创建失败，尚未通知或摘流；已有正式预留保留，可补齐登记后用同一 receipt.id 再创建。

登记和 deployment_revision 不允许 UPDATE / DELETE；应追加新记录或新修订。流程一旦固定原部署、原 hold、恢复修订、需求登记及通知对象，也不能改写。后续登记、current_revision_id 变化不影响进行中的恢复。恢复成功后的实际持有可能仍指向旧修订，这是如实保留原现场；不擅自把新配置当成已部署。

## 流程与状态

创建、分页、详情、advance、stop、cancel 的路径和权限见 [单卡流程接口](A800-compute-single-card-workflow-api.md)。新分支不新增另一个推进接口或调度器。

| 步骤 | 检查及确认 |
|---|---|
| NOTIFY | 用服务端证据复核 R-13、容量、GPU 适配、健康及 R-11；R-05 放行后通知；R-08 检查送达。失败、未知或未及时确认送达直接撤销训练预留，不摘流 |
| DRAIN | 只有通知确认成功才摘流；失败进入撤销健康检查和接流量补偿 |
| WAIT_INFLIGHT | 读取新 DEPLOYMENT 观测，要求原修订、已摘流、在途请求为零，否则持久等待 |
| STOP | 使用固定 N-12 操作；UNKNOWN / RUNNING 只核对原结果，未确认退出和释放 GPU 不进入交接 |
| HANDOVER | 锁节点、各组、申请、作业、流程，重新核验原 hold、预留指针、原批准规则集合、R-05、健康、容量和证据；原部署退出且两卡现场空闲后，原 hold 关闭、训练 OCCUPIED、新 hold 和组指针在一个事务提交 |
| START_JOB / RUNNING | 复用已有训练动作与停止请求；重复推进不重复启动 |
| STOP_JOB / RELEASE | 退出确认后才关闭训练 hold、释放分配并归档作业；流程继续，恢复责任保持 OWED |
| RESTORE_CHECK | 刷新并检查队列；有 B 组或归属未知的 WAITING 候选，返回 QUEUE_ARBITRATION_REQUIRED。没有候选时按原修订及固定需求执行恢复投影、R-06 / R-15 / R-03 / R-11，并写正式 CLOSURE 决策及逐条检查 |
| RESTORE | 通过后先保留原组及恢复容量；发送前再次核验整组健康、容量与现场。通过 N-12 恢复原修订；未知结果保留预留并只查询原操作；成功后转换为原部署的实际 hold |
| HEALTH_CHECK | 恢复成功后检查健康，失败不能接流量 |
| RESUME_TRAFFIC / RESTORED_NOTICE | 确认接流量后恢复状态为 RESTORED，再向固定对象发送恢复通知；通知未确认时进度保留 |
| DONE | 整条责任处理完成；重复推进不产生新动作或新持有 |

详情增加 original_deployment_id、original_hold_id、restore_revision_id、restore_spec_id、recovery_state、last_closure_decision_id。OWED 为尚待恢复；WAITING 为等待资源或仲裁；RESTORING 为恢复资源已保留；CHECKING 为部署已启动但仍待健康和接流量；RESTORED 表示恢复及接流量已确认；NOT_REQUIRED 表示撤销发生在未改变原部署时。训练结束不等于流程完成。

`last_error` 保留等待或异常原因。资源等待时可手动 `advance` 明确触发重评；启用已有轮询后也会按 next_run_at 重评。CLOSURE 的 RESTORE / WAIT_RESOURCE 记录引用原 SUBMISSION，后次重评通过 supersedes 链保留前次判断；原批准仍用于已有动作的版本绑定。R-05 / R-08 执行记录写 resource_event，并带规则执行版本和服务端事实。

恢复资源保留按节点计算到其他组的新预留中，防止外部恢复启动期间容量被抢走。恢复保留转换为实际 hold 在同一事务中完成，不重复计算。恢复责任未完成的原组也不能取得新的 N-07 正式预留。

## 取消、失败与重启

| 情况 | 行为 |
|---|---|
| 通知失败或超时 | 撤销 PENDING，原部署保持持有及接流量；不发送 DRAIN。通知的迟到回执不能重新推进终止流程 |
| 摘流后取消 | 对原部署 HEALTH_CHECK、RESUME_TRAFFIC 确认成功后才撤销；不能只清账本 |
| 停服未知、同时取消 | 查询原 STOP；确认已退出后撤销训练并走恢复；不跳过核对 |
| 训练启动失败 / 未知时取消 | 先核对原启动，取得 STOP_JOB 退出确认后回收，继续恢复 |
| 原部署停服失败 / 训练停止失败 | 保留持有和预留/占用保护，不宣称释放；N-12 确认可安全重试时由 ADMIN 处理 |
| 恢复容量或整组健康不足 | 保留恢复责任和等待原因；RESTORE_CHECK 不启动；已经保留恢复资源后的发送前复核失败也不发送 |
| 恢复启动失败 / 未知 | 保留恢复资源和责任；未知只核对，确认失败且 retryAllowed=true 时才允许管理员安全重试 |
| 恢复后健康失败 / 接流量失败 | 保留已恢复持有，未确认接流量前不宣称完成；恢复责任保留 |
| 有待仲裁候选 | 保留步骤，明确说明 N-09 让位未实现；不删除候选或绕过队首。候选通过现有接口合法取消后可重评 |

进度、租约、操作键均沿用现有持久机制，操作键为 `workflow:{id}:{step}:{row_version}`。过期领取或服务重启继续核对原操作。事务内只有数据库操作和纯判定；节点、通知、模拟观测采集均在持锁资源事务之外。交接异常整体回滚，不能产生半次交接。人工重试继续使用 N-12 的 ADMIN 权限、执行器安全许可及 M-16 审计，不提供强制清账或假装完成的入口。

## 配置、证据及页面

```yaml
compute:
  runtime:
    mode: SIMULATION
  simulator:
    enabled: true
  workflow:
    enabled: false
    poll-delay-ms: 5000
    simulated-evidence-ttl-seconds: 30
```

模拟器及自动推进默认关闭。开启模拟器时，B 流程在需要观测的步骤从持久模拟节点采集新记录；绝不刷新旧观测时间。TTL 为 1～300 秒，仍受已发布 R-11 和数据资产 maxAgeSeconds 限制；复制配置样例后可用 COMPUTE_WORKFLOW_EVIDENCE_TTL_SECONDS 设置。DEPLOYMENT 观测包含 revisionId、running、acceptingTraffic、inFlightRequests、healthy；当前复用 NODE_WATERMARK 的发布来源及年龄策略，单独的部署数据资产留待后续。来源必须显式登记为 A800_SIMULATOR_V1 后发布，不能冒用真实来源。

`GET /api/compute/runtime` 增加 recoveryWorkflowEnabled 能力标记。工作台在此能力启用时展示两卡选项、显式恢复登记、原部署与原组、固定版本、责任状态及异常。VIEWER / REVIEWER 仅查询流程摘要；登记和写操作限 ADMIN / EDITOR，安全重试仅 ADMIN。流程列表/详情移除内部通知对象及领取令牌；Agent 仍走原白名单投影，不加入配置、通知对象或原始回执。

## 隔离验证与操作

必须将 `COMPUTE_ONTOLOGY_MYSQL_URL` 指向有建临时库权限的 MySQL 8 服务，账号和密码从 `COMPUTE_MYSQL_USERNAME` / `COMPUTE_MYSQL_PASSWORD` 注入。辅助类仅创建并清理带随机后缀的 m01_test_business / m01_test_platform 库。测试自行发布隔离规则、声明恢复需求、初始化模拟节点；禁止把测试步骤用于刷新共享库。

```powershell
$env:COMPUTE_ONTOLOGY_MYSQL_URL = 'jdbc:mysql://<测试主机>:<端口>/mysql?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:COMPUTE_MYSQL_USERNAME = '<测试用户>'
$env:COMPUTE_MYSQL_PASSWORD = '<测试密码>'
$env:COMPUTE_MYSQL_SMOKE = 'true'
mvn -f bemodel-server/pom.xml '-Dtest=ComputePreemptionDatabaseTest,ComputeRecoveryMigrationTest,ComputeBWorkflowHttpTest,ComputeWorkflowDatabaseTest' test
```

浏览器验收可对 ComputeBWorkflowHttpTest 单独设置 COMPUTE_BROWSER_DEMO=true。测试完成实际 HTTP 闭环后把临时后端地址写入 `$env:TEMP\b-workflow-browser-ready.txt`；另开 Vite 并设置 VITE_API_TARGET 指向此地址、VITE_PORT 为独立端口。隔离账号密码在测试类中声明，仅供此次测试。界面选择模型 B，显式填写恢复需求，再提交 B 两卡申请，从预留、通知逐步推进到训练运行，停止后继续推进到恢复、健康检查、接流量和 DONE。创建 `$env:TEMP\b-workflow-browser-done.txt` 后测试清理；最长等待 12 分钟，超时计失败。实际执行结果见 [验收记录](A800-compute-acceptance.md)。

若缺少数据库、浏览器或测试失败，必须记录未验证项；不能把条件跳过当作通过。此能力不包含完整让位链、真实执行适配器、全部补偿策略或完整事件闭环。
