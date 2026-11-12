# M-17 运行环境基础

## 改了什么

M-17 把算力运行环境和医疗演示彻底分开，并把“模拟还是真实”做成启动时必须明确回答的开关。

| 内容 | 落地 |
|---|---|
| 运行模式 | `compute.runtime.mode` 只接受 `SIMULATION` / `REAL`；`REAL` 在 N-13 模拟节点与 N-16 真实采集接入前直接拒绝启动 |
| 模拟数据 | `compute.runtime.demo-dataset` 固定标识演示观测来源，默认 `A800_DEMO_FIXTURE`；样例只在 Flyway 首次迁移时插入 |
| 医疗初始化 | `DataSeeder` 仅 `!compute` 加载，算力模式不会写入医疗样例 |
| 医疗定时任务 | `InspectScheduler`（指标/对账巡检）与 `ProposalScheduler`（提案生成）改为 `@Profile("!compute")`，算力模式不存在这些 Bean |
| 医疗控制器 | 新增 `MedicalApiIsolationFilter`：算力模式下医疗专用 API 统一返回 404，不暴露“存在但被拒绝” |
| MCP 工具入口 | `McpConfig`、`BemodelMcpTools` 仅 `!compute` 加载，算力模式没有 `/mcp` 端点 |
| 队列自动重评 | `compute.queue.recheck-enabled` 默认 `false`；需要时显式打开，`recheck-delay-ms` 控制轮询 |
| 启动检查 | `ComputeRuntimeGuard` 打印模式、隔离状态和重启保护，启动日志可直接核对 |
| 重启保护 | Flyway `clean-disabled=true`、`validate-on-migrate=true`；已有管理员密码、数据源登记和业务数据不被启动覆盖 |

医疗隔离入口包括：`/api/alert`、`/api/qc`、`/api/rca`、`/api/flow`、`/api/lab`、`/api/simulation`、`/api/value`、`/api/cs`、`/api/recon`、`/api/search`、`/api/knowledge`、`/api/trace`、`/api/link`、`/api/impact`、`/api/llm/log`、`/api/llm/stats`、`/api/rdf/patient`、`/api/proposal`、`/api/drift`、`/api/reconcile`、`/api/inspect`、`/api/metric`、`/mcp`。本体、规范、映射、治理和算力接口不受影响。

通知中心 `/api/notice/**` 和 LLM 路由健康 `/api/llm/routes` 是全局布局轮询的通用基础设施，算力模式保留可用；只隔离可能含医疗提示词和原始明细的 `/api/llm/log`、`/api/llm/stats`。这样算力页面不会因为顶栏轮询出现“医疗接口未启用”的重复提示。

## 看哪里

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$body = @{ username = 'admin'; password = $env:COMPUTE_ADMIN_PASSWORD } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$baseUrl/api/auth/login" -Method Post -Body $body -ContentType 'application/json'
$headers = @{ Authorization = "Bearer $($login.data.token)" }
Invoke-RestMethod -Uri "$baseUrl/api/compute/runtime" -Headers $headers | ConvertTo-Json -Depth 10
```

返回内容包含：`mode`、`simulation`、`nodeKey`、`demoDataset`、`medicalApisEnabled`、`isolatedMedicalPaths`、`schedulers`、`databases`（只给库名，不含主机和凭据）、`restartProtection`、`realAdapters`、`authorizesExecution=false`。

启动日志另有一行：

```text
算力启动检查通过: 模式=SIMULATION ... 医疗初始化=DISABLED 医疗定时任务=DISABLED ...
```

## 配置

`application-compute.yml.example` 新增：

```yaml
compute:
  runtime:
    mode: ${COMPUTE_RUNTIME_MODE:SIMULATION}
    demo-dataset: ${COMPUTE_DEMO_DATASET:A800_DEMO_FIXTURE}
    medical-apis-enabled: ${COMPUTE_MEDICAL_APIS_ENABLED:false}
  queue:
    recheck-enabled: ${COMPUTE_QUEUE_RECHECK_ENABLED:false}
    recheck-delay-ms: 30000
spring:
  flyway:
    clean-disabled: true
    validate-on-migrate: true
```

实际本机配置仍在被 Git 忽略的 `application-compute.yml`，未把数据库密码或账号写入仓库。

## 重启不覆盖数据

`ComputeRestartProtectionTest` 在随机隔离库验证：

1. 首次启动创建管理员；第二次启动即使传入不同 `COMPUTE_ADMIN_PASSWORD`，管理员数量和密码哈希都不变。
2. 四组八卡样例只由迁移插入一次。
3. 插入一笔申请后再次执行迁移，迁移数为 0，申请、提交时间和样例数量都不变。

没有新增 `RESEED`、`RESET` 或启动时清库逻辑；演示数据与真实运行的区别由运行模式、演示数据标识和隔离库共同保证。

## 验证

- `ComputeRuntimeSettingsTest` 6 项：模式解析、REAL 拒绝、非法配置、接口覆盖、医疗前缀边界、算力/共享接口不被误伤。
- `ComputeRuntimeIsolationTest` 3 项：医疗初始化/定时任务/MCP 的 `@Profile`、运行组件归属、示例配置项。
- `ComputeProfileTest` 1 项：继续验证算力库独立、无医疗样例迁移。
- `ComputeRestartProtectionTest` 1 项：随机隔离库验证重启保护。
- `ComputeMysqlSmokeTest` 17 项：新增 `/api/compute/runtime`、医疗接口 404、`/mcp` 404、`/api/compute/**` 仍可访问、匿名 401；其余 16 项原有 HTTP 用例回归通过。

本机在 MySQL 8.0.40 随机隔离库复跑 17 项 HTTP 冒烟全部通过。首次使用远程 MySQL 8.4 时队列用例在恢复规则状态阶段遇到一次连接超时（16 项通过），改用本地库完整重跑通过；这是测试环境网络问题，不是接口或权限回归。数据库集成测试未设置 `COMPUTE_ONTOLOGY_MYSQL_URL` 时会跳过，跳过不计为通过。

## 边界

- 本轮没有接入真实节点采集、真实容器执行和真实通知；`REAL` 模式被明确拒绝，避免把模拟结果当成实机结果。
- 医疗接口隔离解决的是“算力模式不提供医疗能力”；前端医疗菜单隐藏仍属于 M-15。
- 队列自动重评默认关闭，避免演示环境后台任务在无人值守时改动队列；资源变化事件仍可在同进程内触发一次重评。
