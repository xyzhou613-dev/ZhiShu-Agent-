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
 * M-16 权限目录查询：让页面、验收脚本和后续 Agent 工具看到同一份角色矩阵。
 */
@RestController
@Profile("compute")
@RequestMapping("/api/compute/access-policy")
@ComputeAccess(ComputeOperation.ACCESS_POLICY_QUERY)
final class ComputeAccessController {
    record OperationView(String code, String description, boolean mutating, List<String> roles) {}

    @GetMapping
    Result<Map<String, Object>> catalog() {
        List<OperationView> operations = ComputeOperation.catalog().stream()
                .map(operation -> new OperationView(operation.name(), operation.description(),
                        operation.mutating(), operation.roles().stream().sorted().toList()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", "a800-access-policy-v1");
        result.put("enforcement", "ANNOTATION+INTERCEPTOR");
        result.put("anonymousWritesAllowed", false);
        result.put("agentWritesAllowed", false);
        result.put("operations", operations);
        return Result.ok(result);
    }
}
