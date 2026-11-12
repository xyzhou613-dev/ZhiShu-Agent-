package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M-16 Agent 只读投影入口：复用页面同一份业务查询，但先经过字段敏感目录过滤。
 *
 * <p>这里不是“给 Agent 一套新的业务 API”，而是把已有结果按 {@link ComputeFieldCatalog} 去掉身份、
 * 启动配置、命令、挂载和动作回执原文。后续 N-20 Agent 工具应调用这一层，不能直接把数据库整行交给模型。</p>
 */
@RestController
@Profile("compute")
@RequestMapping("/api/compute/agent")
@ComputeAccess(ComputeOperation.AGENT_QUERY)
final class ComputeAgentController {
    record GroupProjection(String nodeKey, Instant evidenceAsOf, List<JsonNode> groups,
                           boolean authorizesExecution) {}

    private final ComputeGroupQuery groups;
    private final ComputeRequestService requests;
    private final ComputeDatabase.Store store;
    private final ComputeAgentView view;

    ComputeAgentController(ComputeGroupQuery groups, ComputeRequestService requests,
                           ComputeDatabase.Store store, ComputeAgentView view) {
        this.groups = groups;
        this.requests = requests;
        this.store = store;
        this.view = view;
    }

    @GetMapping("/groups")
    Result<GroupProjection> groups(@RequestParam(defaultValue = "a800-demo") String nodeKey,
                                   @RequestParam(required = false) Instant evidenceAsOf) {
        Instant at = evidenceAsOf == null ? Instant.now() : evidenceAsOf;
        List<JsonNode> projected = groups.groups(nodeKey, at).stream()
                .map(group -> view.project(ComputeFieldCatalog.GROUP, group))
                .toList();
        return Result.ok(new GroupProjection(nodeKey, at, projected, false));
    }

    @GetMapping("/requests/{id}")
    Result<JsonNode> request(@PathVariable long id) {
        return Result.ok(view.requestDetail(requests.detail(id)));
    }

    @GetMapping("/actions/results")
    Result<List<JsonNode>> actions(@RequestParam(required = false) Long workflowId,
                                   @RequestParam(required = false) String operationKey) {
        if (workflowId == null && (operationKey == null || operationKey.isBlank())) {
            throw new BizException("请提供流程编号或固定操作编号");
        }
        if (workflowId != null && workflowId <= 0 || operationKey != null && operationKey.length() > 128) {
            throw new BizException("查询参数无效");
        }
        String sql = "SELECT id,workflow_run_id,decision_id,action_type,target_kind,target_id,"
                + "status,attempt_count,started_at,finished_at,error_code FROM execution_action WHERE 1=1";
        var args = new ArrayList<Object>();
        if (workflowId != null) {
            sql += " AND workflow_run_id=?";
            args.add(workflowId);
        }
        if (operationKey != null) {
            sql += " AND idempotency_key=?";
            args.add(operationKey);
        }
        List<Map<String, Object>> rows = store.jdbc().queryForList(sql + " ORDER BY id DESC LIMIT 100", args.toArray());
        return Result.ok(view.actionResults(rows));
    }
}
