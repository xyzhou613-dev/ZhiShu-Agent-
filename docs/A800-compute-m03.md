# M-03 动作与硬约束

## 可以在哪里看

本体管理 → 动作，展开“算力动作条件、执行结果与 26 条硬约束”。10 类动作说明进入条件、关联规则、失败去向；输入流程的固定操作编号，可以查询 `execution_action` 的实际结果。没有回执时显示没有执行记录，不把动作定义当执行成功。

动作包括通知、摘流、停服、交接、启动训练、停止训练、收尾、恢复、健康检查、接回流量。定义保存在平台库 `bm_action.contract_json`；原有三个动作的名称、状态和人工描述不覆盖。全部新增定义保持草稿。

## 26 条约束的落实方式

`GET /api/compute/constraints` 返回 26 条定义、具体检查位置、测试类及业务集成要求；`bm_axiom.enforcement_json` 保存对照记录。

| 约束 | 实际检查位置 | 验证 |
|---|---|---|
| I-1/2/3 | active_job、gpu_allocation、current_decision 的主键/唯一键与外键 | 数据库直接写入及原有申请并发测试 |
| I-4/14 | deployment.active_group_id 唯一；历史退役不覆盖旧部署 | 重复同组部署被拒绝 |
| I-5/23 | hold_period 未关闭唯一；新增区间锁组并查重叠；更新只允许首次写 ended_at；禁止删除 | 直接 SQL 改开始时间、主体、二次关闭、重开、删除、重叠均被拒绝；相邻区间允许 |
| I-6/7 | rule_check 版本外键；R-10 audit 检查快照和证据路径 | ComputeRuleEvaluatorTest；正式决策写入还须在 N-02 事务中调用 |
| I-8 | 作业创建类型必须与申请一致；任务身份/卡数不可原位改写 | 直接 SQL 拒绝不匹配类型与事后修改；改派在专门决策 API 完成前拒绝 |
| I-9/10/17 | R-13 preemption、R-12 queueHead | 仅更高优先级训练抢允许暂停的 A/B 部署；同级、作业、C、单卡均拒绝 |
| I-11/21 | 分配先 PENDING、登记组内使用卡后才可激活；卡数必须等于作业要求；只有 OCCUPIED 可创建持有 | 直接 SQL 验证跨组卡、少卡、提前持有及激活后改明细均被拒绝 |
| I-12 | ComputeNormGuards.decisionReplacement；同申请同类型外键 | 跨申请、跨类型、时间不递增、循环均拒绝；N-02 应锁当前指针后调用 |
| I-13 | ComputeNormGuards.eventSource、事件来源非空与来源决策外键 | 来源缺失或动作与决策不一致被拒绝 |
| I-15 | R-11 freshness | 同一判断时间；过期、未来、未知拒绝 |
| I-16 | R-12 queueHead | 优先级降序、提交时间与编号升序 |
| I-18/25 | R-06 closure 重新调用队首、容量、适配和健康检查 | 不合格队首不让位；恢复不通过进入等待 |
| I-19 | ComputeNormGuards.reservedCards | 差集；重复和组外卡拒绝；保留卡不独立分配 |
| I-20/26 | R-02 capacity、R-15 restore | 分项容量、溢出、整组健康检查；业务提交时须在 M-07 节点锁内取账本计算 |
| I-22/24 | 分配待生效组唯一键、R-07 reservationReceipt | 重复预留拒绝；串行仲裁及队首检查由 N-07 在 M-07 事务锁内接入 |

这里交付的是动作契约、结果查询、约束登记、数据库保护和可复用程序检查。N-07/N-08/N-11/N-12 仍负责真正预留、原子交接和推进流程；它们必须调用这些检查，不能信任页面传入“已经通过”的布尔值。对接要求单列，避免将约束目录误解为整个调度闭环已经运行。

## 迁移与验证

- 平台库新增 V7：10 类动作契约和 26 条约束；不改旧迁移，不自动发布。
- 业务库新增 V3：数据库触发器；不重写历史业务数据。需有 TRIGGER 权限。旧库如有不合规历史记录，应先核对，不能清库规避。
- `ComputeNormDatabaseTest,ComputeNormGuardsTest,ComputeRuleEvaluatorTest` 共 21 项通过。数据库测试使用随机隔离库并清理，涵盖绕过业务入口的直接 SQL。
- `pnpm --dir bemodel-web build` 通过。浏览器自动化环境不可用，未宣称完成视觉点击验收。
- 8 项 HTTP 冒烟回归通过，包含 10 动作/26 约束目录、真实空回执查询和原有申请、取消、并发重试。旧重试测试样例已补齐合法的两卡分配明细。

接口：`GET /api/compute/action-contracts`、`GET /api/compute/constraints`、`GET /api/compute/actions/results?operationKey=...`。回执查询仅 ADMIN/EDITOR；定义和约束沿用已登录只读权限。此变更不会向节点发送命令或通知。
