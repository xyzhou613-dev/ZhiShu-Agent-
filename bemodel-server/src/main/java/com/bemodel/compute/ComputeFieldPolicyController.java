package com.bemodel.compute;

import com.bemodel.common.Result;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M-16 字段敏感标注查询：页面和验收脚本据此确认哪些字段可以进入 Agent 投影。
 */
@RestController
@Profile("compute")
@RequestMapping("/api/compute/field-policies")
@ComputeAccess(ComputeOperation.FIELD_POLICY_QUERY)
final class ComputeFieldPolicyController {
    record PolicyView(String resource, String path, String sensitivity, boolean agentVisible, String note) {}

    @GetMapping
    Result<Map<String, Object>> catalog() {
        Map<String, List<PolicyView>> resources = new LinkedHashMap<>();
        ComputeFieldCatalog.byResource().forEach((resource, policies) -> resources.put(resource,
                policies.stream().map(policy -> new PolicyView(policy.resource(), policy.path(),
                        policy.sensitivity().name(), policy.agentVisible(), policy.note())).toList()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", ComputeVersions.FIELD_POLICY);
        result.put("audience", "AGENT");
        result.put("rule", "只输出 agentVisible=true 的字段；SENSITIVE 与未登记字段一律不进入 Agent；"
                + "页面按角色读取，不因该目录放宽原有权限");
        result.put("resources", resources);
        return Result.ok(result);
    }
}
