package com.bemodel.compute;

import com.bemodel.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * M-17 运行环境只读查询：页面和验收脚本用它确认当前是模拟还是真实模式、医疗接口是否隔离、
 * 两套库是否分离，以及哪些启动保护生效。不返回主机、账号或密码。
 */
@RestController
@Profile("compute")
@RequestMapping("/api/compute/runtime")
final class ComputeRuntimeController {
    record Schedulers(boolean queueRecheckEnabled, String medicalSchedulers, String medicalInitialization) {}

    record Databases(String platformSchema, String businessSchema, boolean separated) {}

    record RuntimeInfo(String version, String mode, boolean simulation, String nodeKey, String demoDataset,
                       boolean medicalApisEnabled, List<String> isolatedMedicalPaths, Schedulers schedulers,
                       Databases databases, boolean anonymousWritesAllowed, boolean agentWritesAllowed,
                       List<String> restartProtection, String realAdapters, boolean simulatorEnabled, boolean authorizesExecution) {}

    private final ComputeRuntimeSettings settings;
    private final ComputeDatabase.Store store;
    private final String platformUrl;
    @Value("${compute.simulator.enabled:false}")
    private boolean simulatorEnabled;

    ComputeRuntimeController(ComputeRuntimeSettings settings, ComputeDatabase.Store store,
                             @Value("${spring.datasource.url:}") String platformUrl) {
        this.settings = settings;
        this.store = store;
        this.platformUrl = platformUrl;
    }

    @GetMapping
    @ComputeAccess(ComputeOperation.RUNTIME_QUERY)
    Result<RuntimeInfo> info() {
        String platformSchema = schema(platformUrl);
        String businessSchema = schema(store.endpoint().jdbcUrl());
        boolean separated = platformSchema != null && businessSchema != null && !platformSchema.equals(businessSchema);
        return Result.ok(new RuntimeInfo(ComputeVersions.RUNTIME, settings.mode().name(), settings.simulation(),
                settings.nodeKey(), settings.demoDataset(), settings.medicalApisEnabled(),
                settings.isolatedMedicalPaths(),
                new Schedulers(settings.queueRecheckEnabled(), "DISABLED_BY_PROFILE", "DISABLED_BY_PROFILE"),
                new Databases(platformSchema, businessSchema, separated),
                false, false,
                List.of("FLYWAY_VERSIONED_ONLY", "EXISTING_ADMIN_PASSWORD_KEPT", "DATASOURCE_REGISTRATION_KEPT",
                        "DEMO_FIXTURE_INSERTED_ONCE"),
                "N-16 NOT_CONNECTED", simulatorEnabled, false));
    }

    private static String schema(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")) {
            return null;
        }
        try {
            URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
            String path = uri.getPath();
            return path == null || path.length() < 2 ? null : path.substring(1);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
