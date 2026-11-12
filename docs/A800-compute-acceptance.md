# A800 算力版任务清单与人工验收

基线为 BeModel `ebbc758`，本地开发分支为 `feat/a800-compute`。第一轮按数据库设计 v0.2 交付迁移、样例、现有建模页面和卡组只读查询。M-01 通过元数据库 V4/V5 补齐对象、属性和关系，M-02 通过 V6 补齐规则及确定性检查绑定。当前平台元数据库迁移到 V9，业务库迁移到 V11：V5 增加规则运行记录，V6/V7 增加证据快照保护和运行证据关联，V8 保存目标组选择，V9 保存容量投影，V10 扩展队列，V11 保存事务预留回执并补充正式决策审计。

## 任务清单

- [x] 独立算力 profile 和元数据库，隔离医疗迁移与启动初始化。
- [x] 算力业务库迁移及一台节点、四组、八卡样例。
- [x] 复用 BeModel 概念、关系、数据源和映射页面。提供卡组只读 API。
- [x] 定向测试及后端构建。提供 MySQL 冒烟测试和人工验收步骤。
- [x] M-01：18 类对象、138 项关键属性和 30 条标准关系，附来源、状态、基数和未落库说明。
- [x] M-02：14 条草稿规则、固定处理函数、版本检查、流程对应关系与只读演练，详见 `docs/A800-compute-m02.md`。
- [x] N-02：固定生效快照执行六阶段规则，保存独立运行记录和逐条结果，提供执行、分页和详情接口。
- [x] N-03：保存服务端证据快照，冻结来源、时间及关键值，接通服务端准入检查及逐条证据引用；正式申请决策后续接入。
- [x] N-04：按生效 R-04 和服务端快照选择目标组，保存等待及抢占待核对结果；实际入队与仲裁留给 N-06。
- [x] N-05：保存方案及恢复容量投影、GPU/显存候选、未知信息和逐条检查；不改变预留、持有或执行状态。
- [x] N-06：节点队列、稳定排序、串行重评、排队取消及可配置的轮询和资源变化触发，见 [队列接口](A800-compute-queue-api.md)。
- [x] N-07：固定规则与新证据，事务核对队首及容量，保存正式准入、提交决策和 PENDING 预留；保留实际部署持有，见 [事务预留接口](A800-compute-reservation-api.md)。
- [ ] 人工在浏览器逐项核对建模页面；自动数据库与 HTTP 验证见文末命令。

## 启动

申请提交、查询、取消和失败作业重试的调用示例见 [N-01 算力申请接口](A800-compute-request-api.md)。

读取生效快照、执行阶段规则和查询运行记录的示例见 [N-02 生效规则运行接口](A800-compute-rule-evaluation-api.md)。

服务端采集证据、查询快照和执行准入检查见 [N-03 证据快照接口](A800-compute-evidence-api.md)。

目标组选择、等待原因及历史查询见 [N-04 目标组选择接口](A800-compute-target-selection-api.md)。

方案与恢复投影、计算分项和历史查询见 [N-05 容量投影接口](A800-compute-capacity-projection-api.md)。

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

1. 检查 MySQL 中 `bemodel_compute_platform` 和 `a800_compute` 的 Flyway 历史。空测试实例应只创建这两个应用库。算力元数据库迁移到 V9，业务库迁移到 V11，应有 41 张业务表（不含 Flyway 历史表）。N-02 的两张表为 `rule_evaluation_run` 和 `rule_evaluation_check`；N-03 复用证据表并新增 `rule_evaluation_check_evidence`；N-04 新增目标选择表；N-05 新增 `capacity_projection_run`、`capacity_projection_check` 和 `capacity_projection_evidence`；N-06 扩展原队列；N-07 新增 `compute_reservation_receipt`。
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

第一轮的 `rule_version`、`decision` 和执行相关表提供后续落库结构。M-04 已支持规则快照发布和激活，N-02 支持调用方事实的独立运行记录，N-07 已接入服务端证据及正式准入、提交决策。实际激活、容器执行和真实采集由后续条目接入。

`ComputeOntologyMigrationTest` 覆盖空库到 V5、V3 到 V5、再次执行无新增迁移、原始概念/属性 ID 和字段映射/快照保留、30 条标准关系及自建关系同名冲突。`ComputeMysqlSmokeTest` 覆盖登录、18 类概念详情、新属性与关系、数据源目录和八卡查询。

未设置对应环境变量时集成测试会跳过，不能把跳过计为已通过。测试中的固定名称业务库初始化器被替换，实际查询使用临时业务库；正常应用启动行为保持原样。

## M-01 验证记录（2026-11-10）

在 MySQL 8.4 随机临时库运行上述 5 个测试类，共 9 项测试，失败 0、错误 0、跳过 0。覆盖 V3→V5 升级、全新初始化、再次迁移无变化、原编号与字段映射及历史快照保留、自建关系及同名冲突保护、真实 HTTP 登录和概念详情，以及八卡状态查询。测试库完成后清理，未迁移现有应用库。浏览器页面逐项检查仍按上方人工验收清单执行。

## M-02 验证记录（2026-11-11）

7 个定向测试类共 27 项通过、无跳过：`ComputeRuleEvaluatorTest`、`ComputeRuleServiceTest`、`ComputeRuleMigrationTest`、`ComputeMysqlSmokeTest`、`ComputeProfileTest`、`ComputeDatabaseTest`、`ComputeGroupQueryTest`。数据库用例在随机隔离库验证，覆盖原始五条规则原位升级、九条新增、再次迁移无变化，以及仅名称/描述被修改或已发布的旧规则保留。HTTP 用例验证 14 条演练、版本漂移拒绝、编辑回草稿、未知处理函数拒绝和未认证请求拒绝。前端 `pnpm build` 通过。

本机使用 JDK 24，包含 Record mock 的 HTTP 测试使用测试 JVM 参数 `-DargLine=-Dnet.bytebuddy.experimental=true` 兼容现有 Byte Buddy；项目目标仍为 Java 21，没有为此升级依赖。其他开发机可使用项目要求的 JDK 21。

本机应用连接共享库，规则表备份后成功迁移至 V6；原 M-01 的 18/138/30 项保留。14 条规则均为草稿，实际 HTTP 样例演练通过；运行规则快照和执行动作仍为零，未执行真实调度。浏览器自动化宿主不可用，尚未完成页面视觉/点击验收。

## N-02 验证记录（2026-11-11）

10 个定向测试类共 45 项用例最终通过，无跳过：`ComputeRuleRunnerTest`、`ComputeRuleEvaluatorTest`、`ComputeRuleServiceTest`、`ComputeRuleEvaluationDatabaseTest`、`ComputeReleaseBridgeTest`、`ComputeOntologyMigrationTest`、`ComputeMysqlSmokeTest`、`ComputeProfileTest`、`ComputeDatabaseTest`、`ComputeGroupQueryTest`。远程连接曾中断，受影响的数据库用例已重跑；其中一次再次迁移检查读取超时，单独重跑后通过，不能将先前失败批次视为整批成功。

MySQL 8.4 随机隔离库验证了业务库 V5、再次迁移无变化、六阶段 14 条规则、快照参数与版本固定、新发布后旧记录保留、跨集合外键保护，以及明细写入失败时主记录和此前明细一起回滚。HTTP 验证了执行、分页、详情、输入限制和角色权限，并回归申请提交、取消、并发提交和失败作业重试。JSON 示例、PowerShell 语法、阶段清单及文档链接已检查。

本次未迁移现有应用库，未在共享库发布或激活演示规则。事实来源为调用方，运行记录的 `authorizesExecution=false`；正式申请决策和服务端证据采集待后续接入。

## N-03 验证记录（2026-11-11）

按确认范围完成服务端证据快照和准入运行。主回归 43 项，另有快照数据库 1 项和空库迁移 1 项，共 45 项定向用例最终通过，无跳过。初次准入数据库用例因远程数据库时钟与应用时钟不一致而失败，改用应用时钟构造隔离观测后单独重跑通过；统计依据为各用例最终报告，不将先前失败批次记为整批成功。

`ComputeEvidenceDatabaseTest` 验证 V5→V6、缺失及过期观测、采集失败、来源不符、GPU 身份错误、字段类型错误、观测字段投影、历史值不覆盖、封存后禁止修改及追加、部分写入整体回滚和再次迁移无变化。固定历史时间只在测试内部使用，接口不接受调用方 asOf。

`ComputeEvidenceAdmissionTest` 与 `ComputeEvidenceEvaluationDatabaseTest` 验证 V6→V7 保留旧 CALLER 记录、从服务端快照生成事实、同一运行固定 asOf 和规则集合、正常通过与证据不合格、逐条引用、跨快照外键保护、新发布后旧结果不变，以及引用写入失败时快照、运行和引用一起回滚。申请状态与正式决策表保持原有状态。

`ComputeMysqlSmokeTest` 的 12 项覆盖实际 HTTP 快照采集、分页、详情、权限、时间注入拒绝和服务端准入，也回归发布、原有六阶段调用方运行、申请、取消、并发提交及失败作业重试。另运行规则判定、规则运行、规则定义、profile、数据库配置和卡组查询测试；`ComputeOntologyMigrationTest#initializesConceptsAndAttributesFromAnEmptySchema` 验证空库最新迁移、35 张业务表、原四组八卡及再次迁移无变化。

测试使用 MySQL 8.4 随机临时库，未迁移现有应用库、未刷新共享观测、未发布共享规则。新增接口及文档中的 JSON、PowerShell、路径、错误信息和链接已核对。正式申请决策、其他阶段的服务端事实生成、真实采集和调度执行仍待后续接入。

## N-04 验证记录（2026-11-11）

按确认范围实现“目标选择＋等待结果”，实际入队和仲裁留给 N-06。11 个定向测试类的最终报告合计 48 项通过，无跳过。空库迁移检查曾因远程连接超时失败，单独重跑后通过；此处统计为最终报告汇总，不将失败批次记为通过。

`ComputeTargetSelectorTest` 的 5 项覆盖 v0.7 卡数表、FLEX 健康/占用/证据/账本阻塞、固定目标不绕组、有效作业卡数、发布布局不符、常驻持有与待生效预留，以及 A 组仅有三张健康卡时的三卡选择。`ComputeEvidenceDatabaseTest` 验证当前作业及有效规格进入封存证据，同时回归原快照保护和回滚。

`ComputeTargetSelectionDatabaseTest` 验证 V7→V8、无生效集合拒绝、申请与作业规格取值、有效观测下选 FLEX、来源不符时回到 B、三张健康卡的 A 组目标、五卡无目标、分页筛选、新发布后旧记录保留、跨快照引用拒绝，以及引用写入失败时快照/选择/引用整体回滚。队列、申请状态及原有分配未被目标选择修改。

13 项 MySQL HTTP 冒烟覆盖新增三个接口、ADMIN/EDITOR 权限、VIEWER/REVIEWER 拒绝、匿名拒绝、详情、分页和输入限制，并回归规则发布、N-02 运行、N-03 快照和准入、申请、取消、并发提交及失败作业重试。测试已适配 M-07 Store 事务入口，并明确将实际业务连接池指向随机测试库。

另运行规则判定、规则运行、规则定义、数据库配置、profile 和卡组查询回归。空库初始化检查验证 37 张业务表、原四组八卡和再次迁移无变化。测试使用隔离 MySQL 8.4；未迁移现有应用库，未刷新共享观测或激活共享规则。目标选择接口文档及 PowerShell 示例已核对。

## N-05 验证记录（2026-11-11）

12 个定向测试类最终报告合计 54 项通过，无跳过。空库本体回归曾发生远程连接中断，清理当次遗留的随机测试库后单独重跑通过；此处按最终报告汇总，不将失败批次记为通过。

`ComputeCapacityProjectorTest` 的 5 项验证已有预留只计一次、条件性扣除拟暂停部署及 R-13 拒绝时不扣除、恢复保留有效分配且不重复累计原基线、全组恢复健康、未知显存/互联、容量不足、其他节点/已关闭分配和溢出，以及优先选择单卡显存满足需求的健康卡而非加总显存。未知显存不填零，未登记的实际互联能力不填 NONE。

`ComputeCapacityProjectionDatabaseTest` 验证 V8→V9、样例方案投影 72 GiB、恢复投影 88 GiB、有效观测及完整需求下检查通过、恢复需求缺项返回未知、分页、历史版本、跨快照引用拒绝和引用写入失败整体回滚。投影不改变队列、有效分配和开放持有区间。

14 项 HTTP 冒烟验证三个新接口、PLAN / RESTORE、详情及分页、角色限制，并回归旧只读容量接口、N-01～N-04、规则发布及卡组查询。另回归规则执行/定义、快照、数据库配置及 profile；空库初始化检查验证 40 张业务表、四组八卡和再次迁移无变化。

测试使用 MySQL 8.4 随机隔离库，未迁移现有应用库、未刷新共享观测、未发布共享规则。JSON、PowerShell 示例、接口路径及文档链接已核对。缺少恢复每卡显存和互联需求时仍返回 UNKNOWN，不将资源投影当作预留、停服或恢复执行许可。

## N-06 / N-07 验证记录（2026-11-11）

13 个定向测试类共 57 项通过，失败、错误及跳过均为 0。包括规则判定、规则快照、选组、容量、队列、事务预留、配置与查询回归；HTTP 冒烟共 16 项。MySQL 8.4 随机临时库验证 V9→V10、V10→V11、空库到最新版本及再次迁移无变化。预留验证覆盖并发重复提交、取消竞争、跨组容量竞争、待生效与实际持有分离、恢复修订固定、证据链引用约束、写入失败整体回滚和历史版本查询。首次预留数据库测试发现决策 INSERT 占位符错误，修复后重新执行并通过，未将失败批次计为通过。

队列接口见 [N-06](A800-compute-queue-api.md)，正式决策与回执见 [N-07](A800-compute-reservation-api.md)。测试使用的有效观测和较长有效期只存在于隔离库；共享样例未刷新，现有应用库未迁移。自动重评默认关闭，浏览器人工验收仍待进行。

## M-17 验证记录（2026-11-12）

按《功能改造与新增清单 v0.2》完成运行环境基础：运行模式开关、医疗初始化/定时任务/控制器/MCP 工具隔离、启动检查和重启保护。详细说明见 [M-17](A800-compute-m17.md)。

- `GET /api/compute/runtime` 返回 `SIMULATION`、医疗隔离清单、两库分离、队列重评关闭、重启保护与 `authorizesExecution=false`；不含数据库主机、账号或密码。
- `ComputeRuntimeSettingsTest` 6 项、`ComputeRuntimeIsolationTest` 3 项、`ComputeProfileTest` 1 项通过。
- `ComputeRestartProtectionTest` 在随机隔离库验证：重复启动不改变管理员密码哈希，四组八卡不重复插入，重新执行迁移后申请与提交时间保留。
- `ComputeMysqlSmokeTest` 17 项通过（新增运行环境与医疗隔离 1 项，回归 16 项）。首次使用远程 MySQL 8.4 时队列用例在恢复规则状态阶段遇到一次连接超时，16 项通过；改用本机 MySQL 8.0.40 随机隔离库完整重跑 17 项通过。网络超时批次未计为通过。
- 医疗接口在算力模式返回 404；`/mcp` 返回 404；`/api/compute/**` 和本体、映射、治理接口不受影响。匿名访问运行环境接口返回 401。
- 没有新增平台或业务迁移；没有刷新共享观测、发布共享规则或执行真实节点动作。
