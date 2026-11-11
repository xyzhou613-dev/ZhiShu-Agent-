# N-05 方案与恢复容量投影

本次交付服务端投影、逐条检查、证据及历史查询。投影不会批准申请、修改队列、预留、分配、持有或执行状态。authorizesExecution=false、queuePersisted=false、readyToExecute=false。

## 口径

所有容量与需求统一为整数字节，判断时间为服务端 UTC asOf，计算使用同一次事务采集的 N-03 快照及固定生效规则集合。

```text
可用内存容量 = 节点物理内存 − 系统保留内存
方案后用量 = 未拟暂停的部署基线 + 其他有效分配预留 + 本次需求
恢复后用量 = 其他部署基线 + 全部有效分配预留 + 指定修订的恢复基线
```

- PENDING 与 OCCUPIED 均计入有效分配；RELEASED / CANCELLED 不计入节点有效预留。
- 本次作业已有有效分配时，目标固定为原分配组，不重新选组；该分配从“其他预留”排除，再作为“本次需求”计入一次。规格与原预留不一致、分配在其他节点或已关闭时拒绝重算，不假装是新作业。
- 没有分配时，复用生效 R-04 选择目标，作业有效规格优先于申请规格。至少五卡没有目标，R-04 返回 FAIL；无目标时 R-03 / R-14 标记 NOT_APPLICABLE / NO_TARGET。
- pausedDeploymentId 是假设，不是停服回执。只允许提出目标组当前持有的部署；通过固定版本 R-13 才在投影中扣除其基线，未通过则保留原基线并记录失败。返回 requiresStopConfirmation=true，不关闭实际持有。
- 恢复不排除当前申请的仍有效分配，不因为“即将结束”就假定资源已释放。指定部署原基线先排除，再加入指定修订一次，避免已持有部署重复累计。
- 水位观测保留在快照中并做 R-11 检查；资源账本按上述公式计算，不把监控已用量再次扣到部署与预留之上。

## GPU、显存和未知数据

PLAN 优先选择健康且每卡显存够用的候选卡，排序按节点卡号；已有明确 allocation_gpu 时保留原使用卡，不暗中换卡。R-14 校验所需卡数及候选使用卡健康，R-03 逐卡校验显存，不能拿多卡总显存替代单卡要求。usedGpuIds 是候选或原分配使用卡，retainedGpuIds 是整组剩余卡，不表示这些卡可另行分配。

R-02 检查内存、临时与持久存储的投影，R-03 同时检查配套资源。恢复的 R-15 使用全组健康和恢复投影，不能用“三张卡健康”代替四卡部署的整组健康。

缺少有效的每卡显存需求时记录 ERROR / UNKNOWN_VRAM_REQUIREMENT；互联未登记时记录 ERROR / UNKNOWN_INTERCONNECT。只有申请明确 gpuInterconnect=NONE 时，才表示无互联要求；不会默认 A800 组具有 NVLink，也不会把缺失字段默认为 NONE。未知显存要求保留 null，实际组互联能力仍标为 UNKNOWN；无互联要求通过不表示已验证硬件互联能力。

当前部署修订没有每卡显存和互联需求字段。RESTORE 可以算出恢复基线、全组卡数和 R-15 结果，但 R-03 记录 ERROR / UNKNOWN_RESTORE_GPU_REQUIREMENTS，不能推断完整恢复可行。恢复配置登记由后续功能补齐。

overallResult 按 ERROR、FAIL、PASS 汇总，对应 feasibility=UNKNOWN、INFEASIBLE、FEASIBLE。FEASIBLE 只说明本次已生成的检查通过；目标组持有、队首、真正停止、原子预留等仍须后续流程检查，不是执行授权。输出和快照会保留目标选择的阻塞状态及拟暂停假设。

## 启用、鉴权和接口

使用 compute profile，业务库最新迁移为 V9，增加 capacity_projection_run、capacity_projection_check、capacity_projection_evidence。旧 GET /api/compute/capacity 保留只读行为，新接口用于保存可追溯记录。

ADMIN / EDITOR 可使用三个接口，VIEWER / REVIEWER 无权调用。先发布并激活完整规则集合，方法见 [N-02 文档](A800-compute-rule-evaluation-api.md)。

| 方法与路径 | 行为 |
|---|---|
| POST /api/compute/capacity-projections | 创建并保存一次投影 |
| GET /api/compute/capacity-projections | 按编号倒序分页，可按 requestId 筛选 |
| GET /api/compute/capacity-projections/{id} | 查询提案、计算分项、逐条事实与结果、证据及快照 |

POST 字段：

| 字段 | 要求 |
|---|---|
| nodeKey | 非空字符串，最多 64 字符，节点必须存在且与生效集合布局一致 |
| requestId | 正整数，申请必须存在 |
| mode | PLAN / RESTORE，默认 PLAN |
| pausedDeploymentId | PLAN 可选，正整数；目标组当前持有的部署 |
| restoreRevisionId | RESTORE 必填正整数；必须属于该节点，不与 pausedDeploymentId 混用 |

不能传入 asOf、snapshotId、facts、evidence；不能覆盖 jobId、gpuCount、targetGroupId、targetGroupCode、capacityBytes、usedGpuIds、ruleSetId、releasedAllocationId。不能靠调用方声称释放了分配来改变容量。

分页 pageNum 默认 1，小于 1 按 1；pageSize 默认 20，小于 1 按 20，最大 200。requestId 筛选参数必须为正整数。列表返回 data.list/total/pageNum/pageSize。

## PowerShell 示例

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$loginBody = @{ username = 'admin'; password = '<管理员密码>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$url = "$baseUrl/api/compute/capacity-projections"
$body = @{ nodeKey = 'a800-demo'; requestId = 1; mode = 'PLAN'; pausedDeploymentId = 2 } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri $url -Headers $headers -ContentType 'application/json' -Body $body
if ($created.code -ne 0) { throw $created.msg }
$created.data | ConvertTo-Json -Depth 40
$id = $created.data.run.id
Invoke-RestMethod -Uri "$url/$id" -Headers $headers
Invoke-RestMethod -Uri "${url}?requestId=1&pageNum=1&pageSize=20" -Headers $headers
```

原样例拟暂停模型 B 时，继续部署基线为 A+C 的 40 GiB，本次已有训练预留 32 GiB 单独计入，投影 72 GiB（77309411328 字节）。节点可用内存为 120 GiB。实际模型 B 的持有仍未结束。

```powershell
$body = @{ nodeKey = 'a800-demo'; requestId = 1; mode = 'RESTORE'; restoreRevisionId = 2 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri $url -Headers $headers -ContentType 'application/json' -Body $body
```

原样例恢复 B 的投影为 A+C 40 GiB，加仍有效训练预留 32 GiB，再加恢复基线 16 GiB，合计 88 GiB（94489280512 字节）。不会将同一训练预留排除或重复计算。

教学观测固定在 2026-11-06，且缺少节点水位；原申请规格也缺少每卡显存和互联要求。接口仍返回可计算的分项，但整体不会当作通过；不会自动刷新旧时间或补造需求。

## 响应与追溯

成功响应使用 code=0、msg="ok"。data 为：

- run：id、requestId、nodeId、mode、targetGroupId/Code、overallResult、feasibility、ruleSetId、snapshotId、asOf、formulaVersion、actor、authorizesExecution、queuePersisted。
- proposal：调用方原提案。
- output：容量、继续部署基线、其他有效预留、本次/恢复需求、projectedBytes、withinCapacity、includedAllocationIds、existingAllocationId（适用时）、GPU 候选与保留卡、未知状态及假设。
- checks：每条 ruleCode、ruleVersionId、执行版本 ruleVersion、定义版本 definitionVersion、result、reasonCode、facts、output。
- evidenceIds / evidenceSnapshot：运行级证据集合及完整固定快照。每条检查的具体输入另存于 checks[].facts，不把运行级引用假称为逐条最小依赖集。

未知互联的检查节选：

```json
{"ruleCode":"R-03","result":"ERROR","reasonCode":"UNKNOWN_INTERCONNECT","output":{"status":"UNKNOWN"}}
```

已执行的判定复用 M-02 固定函数；缺失必要事实时的 UNKNOWN、无目标时的 NO_TARGET 是服务端编排结果，不能当作对应函数已经通过。实际规则版本和公式版本随快照固定。

快照、主记录、全部检查和证据引用在算力库同一事务提交，任一写入失败整体回滚。外键防止跨快照、节点、申请或规则集合混用。历史查询读取保存结果，不以最新规则、观测或规格重新计算。

## 错误与验证

业务错误沿用 HTTP 200、code=500、msg 原因；未登录 401，无权限 403。例如：

```json
{"code":500,"msg":"容量投影记录不存在","data":null}
```

其他错误包括非法 mode、缺少恢复修订、混用提案字段、非目标组持有部署、其他节点或已关闭的分配、预留与作业规格不一致、数值越界/溢出。错误会拒绝本次写入。资源不足或必要观测不可用则保存实际检查结果，供查询解释。

按 [验收说明](A800-compute-acceptance.md) 设置隔离 MySQL 变量，在 bemodel-server 目录运行：

```powershell
mvn '-Dtest=ComputeCapacityProjectorTest,ComputeCapacityProjectionDatabaseTest,ComputeEvidenceDatabaseTest,ComputeMysqlSmokeTest' test
```

覆盖预留去重、条件性扣除、恢复保留有效预留、每卡显存、未知互联、健康、溢出、V8→V9、历史版本、跨快照引用、写入回滚、分页、角色及旧容量查询。有效观测仅在隔离测试库生成，不修改共享样例。
