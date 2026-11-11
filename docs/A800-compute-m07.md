# M-07 独立业务数据源与事务边界

## 改了什么

compute 模式明确提供两套 Hikari 连接池、JDBC 与事务管理器，避免增加业务数据源后让 Spring 自动配置误把业务库当平台库：

| 数据 | 连接池 | JDBC | 事务管理器 |
|---|---|---|---|
| 平台建模、规则编辑、来源登记、发布 STAGED、人工操作审计 | dataSource / platformDataSource（主） | jdbcTemplate / platformJdbcTemplate（主） | transactionManager / platformTransactionManager（主） |
| 申请、作业、分配、持有、决策、证据、执行与运行规则激活 | computeDataSource | computeJdbcTemplate | computeTransactionManager |

原有申请提交、取消、失败重试，卡组与容量查询，发布同步，以及同学新增的规则运行记录写入，全部通过 ComputeDatabase.Store 中同一个业务事务管理器。Store 拒绝 JDBC 与事务管理器指向不同数据源，不能只因为“在一个 @Transactional 方法里”就认为业务库会一起回滚。

## 执行器与预留接口怎么对接

后续 N-07/N-08 注入 `ComputeBusinessTransactions`，使用：

```java
transactions.writeLocked(nodeId, groupId, jdbc -> {
    // 已进入业务事务，按节点共享容量 → 卡组的固定顺序取得行锁。
    // 在这里重新读队首、证据、持有、容量并调用固定规则。
    // 创建预留或关闭旧持有、激活新分配、更新指针、创建新持有。
    // 任一步异常必须抛出，让整笔业务事务回滚。
    return result;
});
```

这是内部 Java 入口，不向用户或 Agent 开放任意 SQL。只有当前节点的目标组可进入回调。`requireBusinessTransaction()` 可校验调用现场；仅有平台事务不能冒充业务事务。其他业务写服务也可明确使用 `@Transactional("computeTransactionManager")`，不要使用无名称的默认平台事务。

节点操作、通知、LLM 调用不能放在持锁事务里。先提交业务记录，事务外调用适配器；操作超时通过固定编号查询原结果，推进和补偿由 N-11/N-12 完成。本项提供事务基础，不新增第二套调度流程。

## 配置

`application-compute.yml.example` 增加 compute.datasource：

- jdbc-url：可配置独立业务 MySQL 地址。留空时从已解析的平台地址派生 a800_compute，并继承连接参数。
- username/password：留空继承平台凭据；需要独立业务账号时单独配置。凭据仍放本机忽略文件或运行环境。
- maximum-pool-size/minimum-idle：默认 4/1；连接等待 8 秒、验证 2 秒、最大生命周期 120 秒、保活 30 秒、JDBC 查询超时 10 秒。
- 业务连接初始化为 UTC。业务地址必须提供独立库名，不允许与平台指向同一主机同一库，不支持在 URL 内嵌用户名密码。

Spring/Flyway 平台迁移仍只用主数据源，业务迁移只在 computeStore 初始化时使用业务池。池由容器在退出时关闭；重复启动验证迁移，不覆盖已有业务记录。

首次注册 A800_COMPUTE 时使用实际业务库的主机、库名和业务账号，不再写死 a800_compute 或误用平台凭据。已有登记保留原凭据（允许另用只读账号）；如果登记主机/端口/库名与运行库不一致，则明确拒绝启动扫描，防止字段映射悄悄指向另一个库。

## 两库发布不使用伪原子事务

继续使用 M-04 的“平台持久化待同步 → 平台提交 → 业务单事务写全快照和切换 → 平台补记同步状态”。业务事务中途失败保留旧指针；业务成功但平台确认失败时，按来源发布和摘要幂等重试，不创建第二份快照。两套本地事务不能保证一起提交，也不会这样宣称。

## 验证

ComputeBusinessTransactionsTest 在随机隔离库中验证：

1. 插入预留并更新组指针后注入异常，两项全部撤销。
2. 关闭原持有、激活分配、插入新持有并改指针后注入异常，原持有重新保持未关闭、原分配仍待生效，使用卡明细也回滚；正常提交后只有一个未关闭持有。
3. 两线程同时争 FLEX，节点和组锁串行化，只有一份待生效预留。
4. 平台事务不被业务事务检查接受，错误节点/组拒绝进入回调，错误数据源组合启动即拒绝。

ComputeMysqlSmokeTest 使用真实的两套 Hikari 池，验证库名、连接身份、事务管理器与 UTC，继续回归发布、来源、资源查询和申请接口。测试强制配置随机业务库，避免 mock Store 却意外初始化共享业务库。

首次完整回归 55 项全部通过；另补三项初始化登记测试，覆盖独立业务凭据、保留已有只读凭据和错误登记阻断。合入同学生效规则执行与运行记录改动（5175992）后，再次运行初始化、规则运行和完整 HTTP 对接测试，保留原资源查询用例。

合入后的 25 项对接测试全部通过（数据库地址 3、初始化 3、HTTP 13、运行记录数据库 2、规则执行器 4）；没有失败或跳过。

本机后端重新打包启动并进入 ACCEPTING_TRAFFIC。实际共享账号登录、14 条规则演练、10 类动作、26 条约束、10 类数据来源、4 个卡组、容量预测、发布状态和运行记录查询通过；原有 1 个账号、18 类对象、138 项属性、30 条关系、1 条申请、1 份分配和 3 条持有记录保留。平台 V9、业务 V5，未自动发布草稿或执行节点动作。本次两轮测试的 20 个临时库均已清理，未删除其他同学的库。

配置测试环境变量的方法见 [算力验收说明](A800-compute-acceptance.md)。从后端目录可运行：

```powershell
mvn '-Dtest=ComputeBusinessTransactionsTest,ComputeDatasourceBootstrapTest,ComputeMysqlSmokeTest' test
```

MySQL 集成测试未设置开关而跳过时不能算通过。JDK 24 下使用旧 Byte Buddy 的本机验证另加 `-DargLine=-Dnet.bytebuddy.experimental=true`；不修改项目 Java 21 基线。
