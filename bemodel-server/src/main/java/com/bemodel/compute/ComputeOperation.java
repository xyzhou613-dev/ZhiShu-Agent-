package com.bemodel.compute;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 算力操作权限目录（M-16）。
 *
 * <p>每个 {@code /api/compute/**} 接口必须通过 {@link ComputeAccess} 声明一个操作；拦截器只认这张目录。
 * 未声明操作的接口按拒绝处理（fail-closed），后续 N-08～N-15 新增流程、停止、重试接口时只需加注解，
 * 不再各自写 {@code CurrentUser.hasAnyRole} 或复制 URL 规则。</p>
 *
 * <p>Demo 复用现有 ADMIN / EDITOR / REVIEWER / VIEWER 角色，不新建员工或项目权限模型。
 * {@link Kind#WRITE} 只表示会改变调度事实，不表示接口是否使用 HTTP POST：
 * 规则演练虽然走 POST，但不落调度数据，归为 {@link Kind#READ}。</p>
 */
enum ComputeOperation {
    RESOURCE_QUERY("资源与卡组查询", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER"),
    CAPACITY_PREVIEW("容量预览", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER"),

    REQUEST_SUBMIT("提交算力申请", Kind.WRITE, "ADMIN", "EDITOR"),
    REQUEST_QUERY("查询算力申请", Kind.READ, "ADMIN", "EDITOR"),
    REQUEST_CANCEL("取消算力申请", Kind.WRITE, "ADMIN", "EDITOR"),
    JOB_RETRY("重试失败作业", Kind.WRITE, "ADMIN", "EDITOR"),
    JOB_STOP("停止运行作业", Kind.WRITE, "ADMIN", "EDITOR"),
    WORKFLOW_QUERY("查询流程进度", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER"),

    ACTION_RESULT_QUERY("查询动作回执", Kind.READ, "ADMIN", "EDITOR"),
    ACTION_RECONCILE("核对未知动作结果", Kind.WRITE, "ADMIN", "EDITOR"),
    ACTION_RETRY("人工重试动作", Kind.WRITE, "ADMIN"),
    ACTION_CONTRACT_QUERY("查询动作与硬约束目录", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER"),

    QUEUE_ENQUEUE("申请入队", Kind.WRITE, "ADMIN", "EDITOR"),
    QUEUE_QUERY("查询队列", Kind.READ, "ADMIN", "EDITOR"),
    QUEUE_REEVALUATE("手动重评队列", Kind.WRITE, "ADMIN", "EDITOR"),

    EVIDENCE_CAPTURE("采集服务端证据", Kind.WRITE, "ADMIN", "EDITOR"),
    EVIDENCE_QUERY("查询证据快照", Kind.READ, "ADMIN", "EDITOR"),

    TARGET_SELECT("选择目标组", Kind.WRITE, "ADMIN", "EDITOR"),
    TARGET_QUERY("查询目标组选择", Kind.READ, "ADMIN", "EDITOR"),

    PROJECTION_RUN("执行容量投影", Kind.WRITE, "ADMIN", "EDITOR"),
    PROJECTION_QUERY("查询容量投影", Kind.READ, "ADMIN", "EDITOR"),

    RULE_EVALUATE("运行规则判定", Kind.WRITE, "ADMIN", "EDITOR"),
    RULE_EVALUATION_QUERY("查询规则运行记录", Kind.READ, "ADMIN", "EDITOR"),
    RULE_CATALOG_QUERY("查询规则目录", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER"),
    RULE_PREVIEW("规则只读演练", Kind.READ, "ADMIN", "EDITOR"),

    RESERVATION_SUBMIT("事务预留与提交决策", Kind.WRITE, "ADMIN", "EDITOR"),
    RESERVATION_QUERY("查询预留回执", Kind.READ, "ADMIN", "EDITOR"),

    RELEASE_QUERY("查询发布与激活历史", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER"),
    RELEASE_PREFLIGHT("发布前检查", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER"),
    RELEASE_SYNC("重试发布同步", Kind.WRITE, "ADMIN", "REVIEWER"),

    RULE_CONFLICT_QUERY("查询规则冲突", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER"),
    RULE_CONFLICT_OPEN("登记规则冲突", Kind.WRITE, "ADMIN", "EDITOR"),
    RULE_CONFLICT_RESOLVE("裁定规则冲突", Kind.WRITE, "ADMIN", "REVIEWER"),

    DATA_ASSET_QUERY("查询数据资产登记", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER"),
    DATA_ASSET_WRITE("修改数据资产登记", Kind.WRITE, "ADMIN", "EDITOR"),

    RUNTIME_QUERY("查询运行环境", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER"),
    ACCESS_POLICY_QUERY("查询权限目录", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER"),
    FIELD_POLICY_QUERY("查询字段敏感标注", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER"),
    AGENT_QUERY("Agent 只读投影", Kind.READ, "ADMIN", "EDITOR", "REVIEWER", "VIEWER");

    enum Kind { READ, WRITE }

    private final String description;
    private final Kind kind;
    private final Set<String> roles;

    ComputeOperation(String description, Kind kind, String... roles) {
        this.description = description;
        this.kind = kind;
        this.roles = Set.of(roles);
    }

    String description() {
        return description;
    }

    boolean mutating() {
        return kind == Kind.WRITE;
    }

    Set<String> roles() {
        return roles;
    }

    boolean allows(String role) {
        return role != null && roles.contains(role.trim().toUpperCase(Locale.ROOT));
    }

    static List<ComputeOperation> catalog() {
        return List.of(values());
    }
}
