# M-16 基础权限与字段敏感标注

## 改了什么

M-16 把算力接口的“谁能做什么”集中到一张操作目录，并给返回字段补上公开 / 内部 / 敏感标注。页面、验收脚本和后续 Agent 读同一份规则，不再在 URL 层、控制器和服务层各写一套。

| 内容 | 落地 |
|---|---|
| 操作目录 | `ComputeOperation`：申请、查询、取消、停止、重试、队列、预留、证据、发布、冲突、数据资产、运行环境、Agent 投影等 |
| 接口声明 | 每个 `/api/compute/**` 方法必须标 `@ComputeAccess(操作)`；也可标在控制器类上 |
| 统一校验 | `ComputeAccessInterceptor` 在进入控制器前校验角色；未声明操作的接口直接拒绝（fail-closed） |
| URL 层 | `SecurityConfig` 对 `/api/compute/**` 只要求登录；细粒度角色统一由拦截器判断，避免与 URL 规则漂移 |
| 拒绝审计 | 被拒绝的操作写 `bm_compute_operation_audit`：谁、什么操作、方法+路径、需要哪些角色 |
| 字段标注 | `ComputeFieldCatalog` 给申请、作业、卡组、动作回执字段标 `PUBLIC` / `INTERNAL` / `SENSITIVE`，并明确 `agentVisible` |
| Agent 投影 | `GET /api/compute/agent/**` 采用正向清单复制字段；目录外字段、敏感字段和整段原始报文不会输出 |
| 目录查询 | `GET /api/compute/access-policy` 查看角色矩阵；`GET /api/compute/field-policies` 查看字段标注 |

Demo 继续复用现有 ADMIN / EDITOR / REVIEWER / VIEWER，不新增员工、项目或配额权限模型。

## 角色矩阵（主要操作）

| 操作 | ADMIN | EDITOR | REVIEWER | VIEWER | 说明 |
|---|---|---|---|---|---|
| 资源与卡组查询 `RESOURCE_QUERY` | ✓ | ✓ | ✓ | ✓ | 只读总览 |
| 提交 / 取消申请 `REQUEST_SUBMIT` / `REQUEST_CANCEL` | ✓ | ✓ |  |  | 会改变申请状态 |
| 申请查询 `REQUEST_QUERY` | ✓ | ✓ |  |  | 含申请人与启动配置，不向只读角色开放 |
| 失败作业重试 `JOB_RETRY` | ✓ | ✓ |  |  | 追加新作业 |
| 停止运行作业 `JOB_STOP` | ✓ | ✓ |  |  | 为 N-10 预留，当前无接口 |
| 流程查询 `WORKFLOW_QUERY` | ✓ | ✓ | ✓ | ✓ | 为 N-11 预留，只读 |
| 动作回执查询 `ACTION_RESULT_QUERY` | ✓ | ✓ |  |  | 原始回执可能含主机和命令 |
| 人工重试动作 `ACTION_RETRY` | ✓ |  |  |  | 为 N-15 预留，只给管理员 |
| 队列 / 预留 / 证据 / 选组 / 容量 / 规则运行 | ✓ | ✓ |  |  | 改变或暴露调度事实 |
| 发布同步 `RELEASE_SYNC` | ✓ |  | ✓ |  | 评审动作 |
| 冲突裁定 `RULE_CONFLICT_RESOLVE` | ✓ |  | ✓ |  | 评审动作 |
| 数据资产写 `DATA_ASSET_WRITE` | ✓ | ✓ |  |  | 修改登记需最新版本 |
| 数据资产查询 / 发布历史 / 规则目录 / 动作目录 | ✓ | ✓ | ✓ | ✓ | 只读 |
| 运行环境 / 权限目录 / 字段目录 / Agent 投影 | ✓ | ✓ | ✓ | ✓ | 只读；Agent 投影另做字段过滤 |

完整矩阵以 `GET /api/compute/access-policy` 为准；目录由代码枚举固定，修改需要改代码并重新测试。

## 字段敏感标注

| 资源 | 敏感字段示例 | 处理 |
|---|---|---|
| 申请 `REQUEST` | `launchSpec`（镜像、命令参数、挂载） | `SENSITIVE`，`agentVisible=false` |
| 申请 `REQUEST` | `employeeId`、`clientRequestKey` | `INTERNAL`，不进入 Agent |
| 作业 `JOB` | 生效资源规格分项 | `INTERNAL`，可进入 Agent |
| 卡组 `GROUP` | `holderRequest.employeeId`、`restoreResponsibility.ownerEmployeeId` | `INTERNAL`，不进入 Agent |
| 卡组 `GROUP` | 卡号、显存、健康、占用、观测时间、保留卡 | `PUBLIC` / `INTERNAL`，可进入 Agent |
| 动作回执 `ACTION_RESULT` | `receipt`（主机、命令、日志原文） | `SENSITIVE`，不进入 Agent |
| 动作回执 `ACTION_RESULT` | `idempotency_key` | `INTERNAL`，不进入 Agent |

Agent 投影使用正向清单：只有登记且 `agentVisible=true` 的字段才会被复制。这样新增 DTO 字段默认不会泄露，必须显式登记后才可能进入 Agent。页面按现有角色读取，不因这份目录放宽权限。

## 接口

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$body = @{ username = 'admin'; password = $env:COMPUTE_ADMIN_PASSWORD } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$baseUrl/api/auth/login" -Method Post -Body $body -ContentType 'application/json'
$headers = @{ Authorization = "Bearer $($login.data.token)" }

# 角色矩阵
Invoke-RestMethod -Uri "$baseUrl/api/compute/access-policy" -Headers $headers | ConvertTo-Json -Depth 8
# 字段标注
Invoke-RestMethod -Uri "$baseUrl/api/compute/field-policies" -Headers $headers | ConvertTo-Json -Depth 8
# Agent 只读投影：不含 launchSpec、employeeId、clientRequestKey
Invoke-RestMethod -Uri "$baseUrl/api/compute/agent/requests/1" -Headers $headers | ConvertTo-Json -Depth 8
# 卡组投影：不含申请人身份，保留卡状态和保留卡
Invoke-RestMethod -Uri "$baseUrl/api/compute/agent/groups?nodeKey=a800-demo" -Headers $headers | ConvertTo-Json -Depth 8
```

`GET /api/compute/agent/actions/results?workflowId=...` 或 `?operationKey=...` 只返回动作状态、类型、时间和错误码，不返回 `receipt` 与 `idempotency_key`。后续 N-20 Agent 工具应调用这一层，不能把数据库整行直接交给模型。

## 后续接口怎么加权限

新增算力接口时：

1. 在 `ComputeOperation` 增加操作，写清中文说明、读写类型和角色；
2. 在控制器方法上加 `@ComputeAccess(新操作)`；
3. 如果返回新字段，在 `ComputeFieldCatalog` 登记敏感等级和是否允许 Agent；
4. 补权限和字段投影测试。

没有声明 `@ComputeAccess` 的接口会返回 403“该算力接口尚未声明访问权限”，这是故意的，避免漏配权限。

## 验证

- `ComputeAccessPolicyTest` 5 项：目录覆盖申请/查询/停止/重试、写操作不向 VIEWER 开放、评审例外的范围、只读目录、角色匹配大小写与空值。
- `ComputeAccessInterceptorTest` 5 项：未声明接口拒绝、VIEWER 提交被拒并审计、EDITOR 提交通过、VIEWER 可读资源但不可读申请、冲突裁定只给 ADMIN/REVIEWER、类级注解生效。
- `ComputeAgentViewTest` 4 项：申请投影去掉启动配置/幂等键/申请人；卡组投影去掉申请人/恢复责任人；动作投影去掉回执/固定操作编号/未知字段；目录一致性。
- `ComputeMysqlSmokeTest` 19 项：新增角色矩阵、拒绝审计、匿名 401、Agent 申请/卡组/动作投影；原有 17 项权限、申请、队列、预留、规则、发布、运行环境回归通过。
- 全量算力测试 125 项在 MySQL 8.0.40 随机隔离库通过，失败、错误、跳过均为 0。

数据库集成测试未设置 `COMPUTE_ONTOLOGY_MYSQL_URL` 时会跳过，跳过不计为通过。JDK 24 下运行 Spring Boot 测试需要 `-DargLine=-Dnet.bytebuddy.experimental=true -Djdk.attach.allowAttachSelf=true` 兼容现有 Mockito/Byte Buddy；项目目标仍为 Java 21。

## 边界

- 本轮是 Demo 基础授权，不绑定真实员工、团队、项目或数据行权限；员工自助和完整权限模型仍属后续范围。
- 前端按角色隐藏按钮、菜单仍属于 M-15；本轮先保证后端接口不出现匿名写操作，敏感字段不随 Agent 结果输出。
- `REVIEWER` 只保留发布同步和冲突裁定两个写例外；其他写操作仍只给 ADMIN / EDITOR。
- 审计记录是平台库的 `bm_compute_operation_audit`，不记录请求体内容，避免把敏感参数写进审计。
