# M-06 卡组详情与统一计算

## 页面和接口

“数据源与映射”上方新增“算力卡组与容量预测”。卡组展开后可分别查看持有使用卡、随组保留卡、待生效分配明细、申请/作业/员工/项目编号、固定恢复修订及每卡物理观测。

部署使用卡按整组登记展示，不能当作逐卡实时利用率。PENDING 的使用卡可以尚未选择，此时整组仍被预留；训练实际使用卡来自 allocation_gpu，剩余卡随组保留。恢复责任优先取当前相关流程固定的 restore_revision_id，其次取持有记录固定的部署修订，不跟随部署最新配置漂移。

- `GET /api/compute/groups`：继续提供原字段，扩展 usage、request、restoreResponsibility。检查真实未关闭持有与待生效分配，并核对组指针；指针不一致返回 GROUP_LEDGER_MISMATCH，不把它当空闲。
- `GET /api/compute/capacity?jobId=1&pausedDeploymentId=2&restoreRevisionId=2`：从服务端当前账本读取容量、部署基线、有效分配与作业规格，预测方案后和恢复后的用量。参数均引用已存在的稳定编号，不接受客户端自报容量或通过结论。

两个查询都明确 `authorizesExecution=false`、`ledgerIsHistorical=false`。观测时点可用于教学查询；账本仍为当前账本，不是历史回放。没有激活规则时，卡组只读查询使用 30 秒教学年龄；有激活集合时读取该集合 R-11 的 maxAgeSeconds。没有运行集合不能据此批准申请。

## 八条公共方法

`ComputeResourceMath` 作为规则、页面和后续 Agent 工具的共同计算位置，版本为 `a800-formulas-v1`，随 M-04 发布固定：

| 口径 | 方法 | 实际调用位置 |
|---|---|---|
| 组可用 | groupAvailable | 卡组查询、R-04 |
| 方案投影 | projection | 容量预测、R-02 |
| 恢复投影 | restoreProjection | 恢复预测、R-15 |
| 可抢占 | preemptible | 预测待暂停部署检查、R-13 |
| 随组保留 | reservedCards | 持有与待生效卡明细；复用 M-03 差集检查 |
| 证据有效 | evidenceValid | 卡组观测、R-11 |
| 队列顺序 | queueOrder | R-12 |
| 合格让位 | qualifiedHandoff | R-06，逐项重查目标、队首、容量、资源和健康 |

字节使用非负 64 位整数；相加溢出拒绝。容量扣除系统保留内存。PENDING/OCCUPIED 分配都计入，RELEASED/CANCELLED 不计。对已经有分配的作业，将该分配从“已有”中排除，再作为“本次”计一次，不能重复扣减。恢复时只加一次恢复基线，尚未释放的分配仍计入。

## 预测的使用限制

- 方案中预计暂停的部署，其基线可从“方案后”预测里扣除；这只是未来方案，实际退出和释放确认前不得把资源交给新任务。
- GPU 显存按每张卡检查，不能把多卡显存相加满足单卡需求。缺失单卡显存或互联要求会明确显示 UNKNOWN，fitVerified=false；容量内不代表所有适配条件已通过。
- 没有完整内存规格时拒绝预测，不补零；临时/持久规格为提交接口允许省略的字段，省略按零。运行前 R-01/R-03 仍需按生效规则补齐与验证完整规格。
- 预测采用一致的只读业务事务；正式预留仍须在 M-07 节点和组锁内重查，不能把页面刚查询的结果直接用于写账本。
- 未新增调度、执行或批准操作；资源首页和完整申请页面由 M-15 接入，可复用本组件和 API。

## 验证

ComputeResourceMathTest 逐条验证八个口径，包括溢出、有效期边界、同时间编号排序、C/单卡/同级不可抢、保留卡差集和不合格让位。ComputeResourceQueryTest 验证实际账本中持有和预留并存、固定恢复修订、指针不一致、观测有效期虽长但年龄已过期，以及预留去重与释放统计。

ComputeRuleEvaluatorTest、ComputeGroupQueryTest 保持回归；ComputeMysqlSmokeTest 验证扩展组查询及 72 GiB 方案投影、88 GiB 恢复投影。前端执行构建检查。

本次共 37 项测试通过（8 项公共口径、2 项实际资源查询、14 项规则、2 项原卡组与 11 项 HTTP 回归）；前端构建通过。浏览器自动化环境不可用，尚未宣称视觉点击验收。
