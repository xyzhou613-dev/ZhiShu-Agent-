# A800 算力版任务清单与人工验收

基线为 BeModel `ebbc758`，本地开发分支为 `feat/a800-compute`。第一轮按数据库设计 v0.2 交付迁移、样例、现有建模页面和卡组只读查询。

## 任务清单

- [x] 独立算力 profile 和元数据库，隔离医疗迁移与启动初始化。
- [x] 算力业务库迁移及一台节点、四组、八卡样例。
- [x] 复用 BeModel 概念、关系、数据源和映射页面。提供卡组只读 API。
- [x] 定向测试及后端构建。提供 MySQL 冒烟测试和人工验收步骤。
- [ ] 人工在独立 MySQL 实例执行迁移与登录验收，确认现有页面及接口结果。

## 启动

Windows 下可双击项目根目录的 `start-compute.cmd`，它会用 `compute` 配置打开后端和前端窗口。执行 `start-compute.cmd -CheckOnly` 可只检查本地启动工具和配置文件。数据库连接使用 Spring 解析后的算力配置，两套库共用同一 MySQL 地址和账号。

在隔离的 MySQL 8 测试实例准备 `bemodel_compute_platform` 和 `a800_compute` 两个空库。应用账号需要在这两个库执行 Flyway 迁移和查询。算力 profile 使用独立配置项。

```powershell
$env:SPRING_PROFILES_ACTIVE = 'compute'
$env:COMPUTE_MYSQL_HOST = '127.0.0.1'
$env:COMPUTE_MYSQL_PORT = '3306'
$env:COMPUTE_MYSQL_USERNAME = '<测试库账号>'
$env:COMPUTE_MYSQL_PASSWORD = '<测试库密码>'
$env:COMPUTE_ADMIN_PASSWORD = '<首次启动管理员密码，至少 12 字符>'
$env:JWT_SECRET = '<JWT 密钥>'
$env:APP_SECRET_KEY = '<数据源凭证加密密钥>'
```

从 `bemodel-server` 运行 `mvn spring-boot:run`。首次启动使用 `COMPUTE_ADMIN_PASSWORD` 创建 `admin`。如果库里已有管理员，启动保留已有密码。

从 `bemodel-web` 用项目锁文件安装依赖，再运行 `pnpm dev`。进入现有页面，用 `admin` 登录。算力 profile 提供算力元数据。医疗菜单及代码仍在原应用中，人工验收选择下面的建模和数据源入口。

## 人工验收

1. 检查 MySQL 中 `bemodel_compute_platform` 和 `a800_compute` 的 Flyway 历史。空测试实例应只创建这两个应用库。算力业务库应有 28 张业务表。
2. 本体管理应出现「算力管理」域，可以找到节点、卡组和 GPU 等概念及其关系。打开数据源与映射页面，确认 `A800_COMPUTE` 可见，`COMPUTE_GPU_DEVICE.UUID` 对应 `gpu_device.gpu_uuid`。
3. 调用已登录的查询。固定样例观测时间为 `2026-11-06T10:00:00Z`，有效 30 秒。接口以当前账本为准，`evidenceAsOf` 只控制读取和校验哪一时点的观测。

```powershell
$baseUrl = 'http://127.0.0.1:18080'
$body = @{ username = 'admin'; password = $env:COMPUTE_ADMIN_PASSWORD } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$baseUrl/api/auth/login" -Method Post -Body $body -ContentType 'application/json'
$headers = @{ Authorization = "Bearer $($login.data.token)" }
$overview = Invoke-RestMethod -Uri "$baseUrl/api/compute/groups?evidenceAsOf=2026-11-06T10:00:00Z" -Headers $headers
$overview.data.groups | ConvertTo-Json -Depth 10
```

| 验收项 | 预期 |
|---|---|
| 节点卡组 | A、B、C、FLEX 四组共八张卡 |
| B 组 | 模型 B 是实际持有者，训练申请 1 是待生效预留，原因包含 `GROUP_HELD` 和 `GROUP_RESERVED` |
| FLEX 组 | 有效样例观测下 `directlyAllocatable=true` |
| 证据过期 | 将时间改为 `2026-11-06T10:00:31Z`，FLEX 组不可直接分配，原因包含 `GPU_EVIDENCE_UNAVAILABLE` |
| 重启 | Flyway 不重复插入样例，已创建的管理员继续有效 |

在同一干净测试实例可运行端到端冒烟测试。它覆盖两套迁移、登录、概念查询、数据源目录和卡组接口。

```powershell
$env:COMPUTE_MYSQL_SMOKE = 'true'
mvn '-Dtest=ComputeMysqlSmokeTest' test
```

第一轮的 `rule_version`、`decision` 和执行相关表提供后续落库结构。调度审批、规则快照发布、容器执行和真实采集属于下一轮。
