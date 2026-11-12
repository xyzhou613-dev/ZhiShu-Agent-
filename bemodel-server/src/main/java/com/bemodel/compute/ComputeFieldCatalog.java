package com.bemodel.compute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M-16 字段敏感目录：算力接口响应字段的公开 / 内部 / 敏感标注。
 *
 * <p>Agent 只读投影采用正向清单——只有 {@code agentVisible=true} 的字段才会被复制；
 * 未登记的字段、敏感字段和整段原始报文一律不输出。目录随构建版本固定，
 * 新增接口字段时应同时补登记，否则不会出现在 Agent 结果中。</p>
 */
final class ComputeFieldCatalog {
    static final String REQUEST = "REQUEST";
    static final String JOB = "JOB";
    static final String GROUP = "GROUP";
    static final String ACTION_RESULT = "ACTION_RESULT";

    private ComputeFieldCatalog() {
    }

    private static final List<ComputeFieldPolicy> POLICIES = List.of(
            // 申请：身份和启动配置属于敏感边界；资源规格和状态可供 Agent 解释。
            p(REQUEST, "id", ComputeSensitivity.PUBLIC, true, "申请编号"),
            p(REQUEST, "projectId", ComputeSensitivity.INTERNAL, true, "项目归属，不含成本中心明细"),
            p(REQUEST, "taskTypeId", ComputeSensitivity.INTERNAL, true, "任务类型编号"),
            p(REQUEST, "requestedGpuCount", ComputeSensitivity.PUBLIC, true, "申请卡数"),
            p(REQUEST, "requestedResourceSpec.vramBytesPerGpu", ComputeSensitivity.INTERNAL, true, "每卡显存需求"),
            p(REQUEST, "requestedResourceSpec.memoryBytes", ComputeSensitivity.INTERNAL, true, "主机内存需求"),
            p(REQUEST, "requestedResourceSpec.temporaryBytes", ComputeSensitivity.INTERNAL, true, "临时磁盘需求"),
            p(REQUEST, "requestedResourceSpec.persistentBytes", ComputeSensitivity.INTERNAL, true, "持久存储需求"),
            p(REQUEST, "requestedResourceSpec.gpuInterconnect", ComputeSensitivity.INTERNAL, true, "互联要求"),
            p(REQUEST, "status", ComputeSensitivity.PUBLIC, true, "申请状态"),
            p(REQUEST, "submittedAt", ComputeSensitivity.PUBLIC, true, "提交时间（UTC）"),
            p(REQUEST, "employeeId", ComputeSensitivity.INTERNAL, false, "申请人身份；Agent 不需要，默认不输出"),
            p(REQUEST, "clientRequestKey", ComputeSensitivity.INTERNAL, false, "幂等键，避免外部重放"),
            p(REQUEST, "launchSpec", ComputeSensitivity.SENSITIVE, false, "镜像、命令参数、挂载整段不进入 Agent"),

            // 作业：执行结果给 Agent；资源规格只给分项，不给启动配置。
            p(JOB, "id", ComputeSensitivity.PUBLIC, true, "作业编号"),
            p(JOB, "requestId", ComputeSensitivity.INTERNAL, true, "所属申请"),
            p(JOB, "taskTypeId", ComputeSensitivity.INTERNAL, true, "任务类型编号"),
            p(JOB, "effectiveGpuCount", ComputeSensitivity.PUBLIC, true, "生效卡数"),
            p(JOB, "effectiveResourceSpec.vramBytesPerGpu", ComputeSensitivity.INTERNAL, true, "生效每卡显存"),
            p(JOB, "effectiveResourceSpec.memoryBytes", ComputeSensitivity.INTERNAL, true, "生效主机内存"),
            p(JOB, "effectiveResourceSpec.temporaryBytes", ComputeSensitivity.INTERNAL, true, "生效临时磁盘"),
            p(JOB, "effectiveResourceSpec.persistentBytes", ComputeSensitivity.INTERNAL, true, "生效持久存储"),
            p(JOB, "effectiveResourceSpec.gpuInterconnect", ComputeSensitivity.INTERNAL, true, "生效互联要求"),
            p(JOB, "status", ComputeSensitivity.PUBLIC, true, "作业状态"),
            p(JOB, "createdAt", ComputeSensitivity.PUBLIC, true, "创建时间"),
            p(JOB, "startedAt", ComputeSensitivity.PUBLIC, true, "开始时间"),
            p(JOB, "endedAt", ComputeSensitivity.PUBLIC, true, "结束时间"),

            // 卡组：资源状态给 Agent；申请人身份、恢复责任人身份不输出。
            p(GROUP, "code", ComputeSensitivity.PUBLIC, true, "卡组编码 A/B/C/FLEX"),
            p(GROUP, "groupId", ComputeSensitivity.INTERNAL, true, "卡组编号"),
            p(GROUP, "holder.kind", ComputeSensitivity.PUBLIC, true, "持有主体类型"),
            p(GROUP, "holder.id", ComputeSensitivity.INTERNAL, true, "持有主体编号"),
            p(GROUP, "holder.name", ComputeSensitivity.INTERNAL, true, "部署名称"),
            p(GROUP, "reservation.allocationId", ComputeSensitivity.INTERNAL, true, "待生效分配编号"),
            p(GROUP, "reservation.jobId", ComputeSensitivity.INTERNAL, true, "待生效作业编号"),
            p(GROUP, "reservation.requestId", ComputeSensitivity.INTERNAL, true, "待生效申请编号"),
            p(GROUP, "cards[].uuid", ComputeSensitivity.INTERNAL, true, "GPU 稳定标识"),
            p(GROUP, "cards[].index", ComputeSensitivity.PUBLIC, true, "节点内卡号"),
            p(GROUP, "cards[].vramBytes", ComputeSensitivity.PUBLIC, true, "显存容量"),
            p(GROUP, "cards[].observation.source", ComputeSensitivity.INTERNAL, true, "观测来源"),
            p(GROUP, "cards[].observation.observedAt", ComputeSensitivity.PUBLIC, true, "采集时间"),
            p(GROUP, "cards[].observation.validUntil", ComputeSensitivity.PUBLIC, true, "有效期"),
            p(GROUP, "cards[].observation.status", ComputeSensitivity.PUBLIC, true, "观测状态"),
            p(GROUP, "cards[].observation.healthy", ComputeSensitivity.PUBLIC, true, "健康标记"),
            p(GROUP, "cards[].observation.occupied", ComputeSensitivity.PUBLIC, true, "占用标记"),
            p(GROUP, "directlyAllocatable", ComputeSensitivity.PUBLIC, true, "当前是否可直接分配"),
            p(GROUP, "reasons", ComputeSensitivity.PUBLIC, true, "不可分配原因码"),
            p(GROUP, "ledgerAvailable", ComputeSensitivity.PUBLIC, true, "账本是否可用"),
            p(GROUP, "ledgerConsistent", ComputeSensitivity.PUBLIC, true, "账本指针是否一致"),
            p(GROUP, "maxObservationAgeSeconds", ComputeSensitivity.PUBLIC, true, "证据最大年龄"),
            p(GROUP, "holderUsage.basis", ComputeSensitivity.PUBLIC, true, "使用卡口径"),
            p(GROUP, "holderUsage.usedGpuUuids", ComputeSensitivity.INTERNAL, true, "持有使用卡"),
            p(GROUP, "holderUsage.retainedGpuUuids", ComputeSensitivity.INTERNAL, true, "随组保留卡"),
            p(GROUP, "pendingUsage.basis", ComputeSensitivity.PUBLIC, true, "预留使用卡口径"),
            p(GROUP, "pendingUsage.usedGpuUuids", ComputeSensitivity.INTERNAL, true, "预留使用卡"),
            p(GROUP, "pendingUsage.retainedGpuUuids", ComputeSensitivity.INTERNAL, true, "预留保留卡"),
            p(GROUP, "holderRequest.requestId", ComputeSensitivity.INTERNAL, true, "持有申请编号"),
            p(GROUP, "holderRequest.jobId", ComputeSensitivity.INTERNAL, true, "持有作业编号"),
            p(GROUP, "holderRequest.projectId", ComputeSensitivity.INTERNAL, true, "持有项目编号"),
            p(GROUP, "holderRequest.employeeId", ComputeSensitivity.INTERNAL, false, "持有申请人身份不输出"),
            p(GROUP, "pendingRequest.requestId", ComputeSensitivity.INTERNAL, true, "预留申请编号"),
            p(GROUP, "pendingRequest.jobId", ComputeSensitivity.INTERNAL, true, "预留作业编号"),
            p(GROUP, "pendingRequest.projectId", ComputeSensitivity.INTERNAL, true, "预留项目编号"),
            p(GROUP, "pendingRequest.employeeId", ComputeSensitivity.INTERNAL, false, "预留申请人身份不输出"),
            p(GROUP, "restoreResponsibility.deploymentId", ComputeSensitivity.INTERNAL, true, "待恢复部署"),
            p(GROUP, "restoreResponsibility.revisionId", ComputeSensitivity.INTERNAL, true, "固定恢复版本"),
            p(GROUP, "restoreResponsibility.revision", ComputeSensitivity.PUBLIC, true, "恢复版本号"),
            p(GROUP, "restoreResponsibility.workflowId", ComputeSensitivity.INTERNAL, true, "关联流程"),
            p(GROUP, "restoreResponsibility.workflowStatus", ComputeSensitivity.PUBLIC, true, "流程状态"),
            p(GROUP, "restoreResponsibility.ownerEmployeeId", ComputeSensitivity.INTERNAL, false, "恢复责任人身份不输出"),

            // 动作回执：状态与错误码给 Agent；回执原文和幂等键不输出。
            p(ACTION_RESULT, "id", ComputeSensitivity.PUBLIC, true, "动作编号"),
            p(ACTION_RESULT, "workflow_run_id", ComputeSensitivity.INTERNAL, true, "流程编号"),
            p(ACTION_RESULT, "decision_id", ComputeSensitivity.INTERNAL, true, "来源决策"),
            p(ACTION_RESULT, "action_type", ComputeSensitivity.PUBLIC, true, "动作类型"),
            p(ACTION_RESULT, "target_kind", ComputeSensitivity.PUBLIC, true, "目标类型"),
            p(ACTION_RESULT, "target_id", ComputeSensitivity.INTERNAL, true, "目标编号"),
            p(ACTION_RESULT, "status", ComputeSensitivity.PUBLIC, true, "动作状态"),
            p(ACTION_RESULT, "attempt_count", ComputeSensitivity.PUBLIC, true, "尝试次数"),
            p(ACTION_RESULT, "started_at", ComputeSensitivity.PUBLIC, true, "开始时间"),
            p(ACTION_RESULT, "finished_at", ComputeSensitivity.PUBLIC, true, "结束时间"),
            p(ACTION_RESULT, "error_code", ComputeSensitivity.INTERNAL, true, "错误码，不含原始输出"),
            p(ACTION_RESULT, "idempotency_key", ComputeSensitivity.INTERNAL, false, "固定操作编号不输出"),
            p(ACTION_RESULT, "receipt", ComputeSensitivity.SENSITIVE, false, "节点回执原文可能含主机、命令和日志")
    );

    static List<ComputeFieldPolicy> all() {
        return POLICIES;
    }

    static List<ComputeFieldPolicy> forResource(String resource) {
        return POLICIES.stream().filter(policy -> policy.resource().equals(resource)).toList();
    }

    static Map<String, List<ComputeFieldPolicy>> byResource() {
        Map<String, List<ComputeFieldPolicy>> result = new LinkedHashMap<>();
        for (ComputeFieldPolicy policy : POLICIES) {
            result.computeIfAbsent(policy.resource(), key -> new ArrayList<>()).add(policy);
        }
        return result;
    }

    private static ComputeFieldPolicy p(String resource, String path, ComputeSensitivity sensitivity,
                                        boolean agentVisible, String note) {
        return ComputeFieldPolicy.of(resource, path, sensitivity, agentVisible, note);
    }
}
