# M-04 规则发布、同步与运行生效

## 页面操作

1. 本体管理 → 规则，将所需 14 条规则按草稿、评审、发布的流程发布。规则发布/废弃需要 ADMIN 或 REVIEWER；编辑不直接激活。
2. 本体管理 → 发布，查看“算力运行规则”里的缺项。规则处理函数/版本错误、缺少规则、负责人无效、A/B/C/FLEX 卡组不匹配、训练等级不高于部署，或存在未解决规则冲突时，不能发布运行版本。
3. 使用原来的“发布新版本”。后端保存平台快照并提交成功后，才同步业务库。在业务库的同一事务里写齐 14 个不可变执行版本、集合成员、激活历史，最后切换生效指针。
4. 同步失败会显示 FAILED 和原因；核对后点击“重试原快照”。重试使用已发布快照，不重新读取正在编辑的草稿。重复点击不增加规则快照或激活记录。

页面将“平台最新发布”与“当前运行集合”分别显示。执行快照可以通过 `GET /api/compute/releases/sets/{id}` 查询，含 14 个实际执行版本。N-02 每次决策须固定集合 ID，并把使用的 `rule_version.id` 写入 `rule_check`，不能执行时每条规则再找最新版本。

## 版本与事务

- 平台库 V8：发布互斥行、持久同步状态、规则冲突记录、人工操作审计。
- 业务库 V4：rule_set、rule_set_member、active_rule_set、rule_activation。原 rule_version 继续作为执行快照；内容和删除由数据库触发器保护。
- 规则定义版本与执行快照版本分别记录。每次新发布都生成新的执行版本，即使重新启用相同内容，也不会改旧决策引用的版本。
- 规则实现 `a800-m02-v1`、计算口径 `a800-formulas-v1`、训练/部署等级、组 ID/卡数、负责人和预期上一集合都进入快照。当前 Demo 默认规则负责人员工编号为 1、节点 a800-demo；可通过 `compute.rules.owner-employee-id`、`compute.rules.node-key` 配置，发布时验证实际记录。
- 两库不假装拥有一个原子事务。平台先持久化 STAGED，提交后同步；业务同步中途失败整体回滚，原指针不变。业务已提交但平台确认失败时，重试会识别已存在的集合并补记状态。
- 较旧发布如果预期上一集合已变化，会被阻断，不能覆盖较新生效集合。已同步的旧发布重试只返回原结果，不重新激活旧规则；回到旧内容也应创建新发布。
- 不自动发布现有草稿，不在启动时切换规则，不在共享库制造演示发布记录。

## 规则冲突与审计

`GET/POST /api/compute/rule-conflicts` 查询/登记冲突，body 为 `ruleCodes`（至少两个有效编号）、`description`、`priority`。

`POST /api/compute/rule-conflicts/{id}/resolve` 需 ADMIN/REVIEWER，body 为 `resolution`。保留登记人、处理人、时间和结论；未处理冲突阻断下一次发布。发布、同步、冲突处理追加到 bm_compute_operation_audit，不依赖 LLM 日志。

其他接口：`GET /api/compute/releases`、`GET /api/compute/releases/preflight`、`POST /api/compute/releases/{平台发布ID}/sync`。同步重试需要 ADMIN/REVIEWER。

## 验证

`ComputeReleaseBridgeTest` 在随机隔离两库验证：复制到第 13 条时故障导致所有新增快照和集合回滚；修复后重试成功；重复同步不重复；新发布保留旧内容；旧发布不能覆盖新集合；平台回滚不激活；缺规则、草稿、未知处理函数版本、冲突、错误等级均被阻断。

再运行 `ComputeMysqlSmokeTest` 验证实际 HTTP 发布、自动同步、集合查询、重复同步和原有申请接口；前端执行 `pnpm --dir bemodel-web build`。

本次验证：6 项发布与定义测试、9 项 HTTP 回归通过；前端构建通过。测试发布只在随机隔离库中产生，未把共享库中的草稿自动发布。
