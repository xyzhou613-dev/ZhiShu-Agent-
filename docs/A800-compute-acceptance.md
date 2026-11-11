# A800 算力版任务清单与人工验收

基线为 BeModel `ebbc758`，本地开发分支为 `feat/a800-compute`。第一轮按数据库设计 v0.2 交付迁移、样例、现有建模页面和卡组只读查询。M-01 通过元数据库 V4/V5 补齐对象、属性和关系，M-02 通过 V6 补齐规则及确定性检查绑定，业务库仍为 V2。

## 任务清单

- [x] 独立算力 profile 和元数据库，隔离医疗迁移与启动初始化。
- [x] 算力业务库迁移及一台节点、四组、八卡样例。
- [x] 复用 BeModel 概念、关系、数据源和映射页面。提供卡组只读 API。
- [x] 定向测试及后端构建。提供 MySQL 冒烟测试和人工验收步骤。
- [x] M-01：18 类对象、138 项关键属性和 30 条标准关系，附来源、状态、基数和未落库说明。
- [x] M-02：14 条草稿规则、固定处理函数、版本检查、流程对应关系与只读演练，详见 `docs/A800-compute-m02.md`。
- [ ] 人工在浏览器逐项核对建模页面；自动数据库与 HTTP 验证见文末命令。

## 启动

首次检出后，将 `bemodel-server/src/main/resources/application-compute.yml.example` 复制为同目录下的 `application-compute.yml`。实际配置由 Git 忽略。

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

1. 检查 MySQL 中 `bemodel_compute_platform` 和 `a800_compute` 的 Flyway 历史。空测试实例应只创建这两个应用库。算力元数据库迁移到 V6，业务库迁移到 V2，应有 28 张业务表。
2. 本体管理应出现「算力管理」域，含 18 类概念。参照 `docs/A800-compute-ontology-checklist.md` 核对 138 项属性和 30 条标准关系；含用户自建关系的库允许多于 30 条。打开数据源与映射页面，确认 `A800_COMPUTE` 可见，`COMPUTE_GPU_DEVICE.UUID` 对应 `gpu_device.gpu_uuid`。
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

打开「资源分配」属性，确认 PENDING 是待生效预留、OCCUPIED 才实际持有；打开「项目」关系，确认项目→申请；打开「资源事件」，确认事件触发决策与决策产生事件分开，未落库关系有明确说明。概念的“已发布”是定义状态，不是任务正在运行。

数据库迁移和端到端冒烟测试统一使用随机命名的临时库。测试账号需有创建和删除测试库的权限；不会操作现有 `bemodel_compute_platform` 或 `a800_compute`。测试结束清理自身创建的库，非正常中断时可按输出中的确切测试库名核对清理。不要将实际密码写入仓库。

```powershell
$env:COMPUTE_ONTOLOGY_MYSQL_URL = 'jdbc:mysql://<测试实例地址>:<端口>/mysql?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=10000&socketTimeout=30000'
$env:COMPUTE_MYSQL_USERNAME = '<测试账号>'
$env:COMPUTE_MYSQL_PASSWORD = '<测试密码>'
$env:COMPUTE_MYSQL_SMOKE = 'true'
mvn '-Dtest=ComputeOntologyMigrationTest,ComputeMysqlSmokeTest,ComputeProfileTest,ComputeDatabaseTest,ComputeGroupQueryTest' test
```

第一轮的 `rule_version`、`decision` 和执行相关表提供后续落库结构。调度审批、规则快照发布、容器执行和真实采集属于下一轮。

`ComputeOntologyMigrationTest` 覆盖空库到 V5、V3 到 V5、再次执行无新增迁移、原始概念/属性 ID 和字段映射/快照保留、30 条标准关系及自建关系同名冲突。`ComputeMysqlSmokeTest` 覆盖登录、18 类概念详情、新属性与关系、数据源目录和八卡查询。

未设置对应环境变量时集成测试会跳过，不能把跳过计为已通过。测试中的固定名称业务库初始化器被替换，实际查询使用临时业务库；正常应用启动行为保持原样。

## M-01 验证记录（2026-11-10）

在 MySQL 8.4 随机临时库运行上述 5 个测试类，共 9 项测试，失败 0、错误 0、跳过 0。覆盖 V3→V5 升级、全新初始化、再次迁移无变化、原编号与字段映射及历史快照保留、自建关系及同名冲突保护、真实 HTTP 登录和概念详情，以及八卡状态查询。测试库完成后清理，未迁移现有应用库。浏览器页面逐项检查仍按上方人工验收清单执行。

## M-02 验证记录（2026-11-11）

7 个定向测试类共 27 项通过、无跳过：`ComputeRuleEvaluatorTest`、`ComputeRuleServiceTest`、`ComputeRuleMigrationTest`、`ComputeMysqlSmokeTest`、`ComputeProfileTest`、`ComputeDatabaseTest`、`ComputeGroupQueryTest`。数据库用例在随机隔离库验证，覆盖原始五条规则原位升级、九条新增、再次迁移无变化，以及仅名称/描述被修改或已发布的旧规则保留。HTTP 用例验证 14 条演练、版本漂移拒绝、编辑回草稿、未知处理函数拒绝和未认证请求拒绝。前端 `pnpm build` 通过。

本机使用 JDK 24，包含 Record mock 的 HTTP 测试使用测试 JVM 参数 `-DargLine=-Dnet.bytebuddy.experimental=true` 兼容现有 Byte Buddy；项目目标仍为 Java 21，没有为此升级依赖。其他开发机可使用项目要求的 JDK 21。

本机应用连接共享库，规则表备份后成功迁移至 V6；原 M-01 的 18/138/30 项保留。14 条规则均为草稿，实际 HTTP 样例演练通过；运行规则快照和执行动作仍为零，未执行真实调度。浏览器自动化宿主不可用，尚未完成页面视觉/点击验收。
