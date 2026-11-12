package com.bemodel.compute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 算力运行环境开关（M-17）。
 *
 * <p>运行模式只允许显式配置，页面、Agent 或调用方都不能在请求里临时切换。真实节点采集与执行适配器
 * （N-13、N-16）尚未接入，因此 {@code REAL} 会在启动时被拒绝，避免误把模拟结果当成真实运行。</p>
 *
 * <p>医疗专用接口在算力模式下默认关闭；只有明确做跨域联调时才通过
 * {@code compute.runtime.medical-apis-enabled=true} 打开。</p>
 */
@Component
@Profile("compute")
final class ComputeRuntimeSettings {
    enum Mode { SIMULATION, REAL }

    /**
     * 算力运行模式不启用的医疗专用入口；边界匹配在 {@link #matchedIsolatedPath(String)}。
     *
     * <p>通知中心和 LLM 路由健康是全局布局轮询的通用基础设施，不能因为算力模式而返回 404；
     * 只隔离 LLM 调用日志/统计这类可能包含医疗提示词与原始明细的接口。</p>
     */
    static final List<String> ISOLATED_MEDICAL_PATHS = List.of(
            "/api/alert", "/api/qc", "/api/rca", "/api/flow", "/api/lab", "/api/simulation",
            "/api/value", "/api/cs", "/api/recon", "/api/search", "/api/knowledge", "/api/trace",
            "/api/link", "/api/impact", "/api/llm/log", "/api/llm/stats", "/api/rdf/patient",
            "/api/proposal", "/api/drift", "/api/reconcile", "/api/inspect", "/api/metric", "/mcp");

    private static final Logger LOG = LoggerFactory.getLogger(ComputeRuntimeSettings.class);

    private final Mode mode;
    private final String nodeKey;
    private final String demoDataset;
    private final boolean medicalApisEnabled;
    private final boolean queueRecheckEnabled;

    ComputeRuntimeSettings(@Value("${compute.runtime.mode:SIMULATION}") String mode,
                           @Value("${compute.runtime.node-key:${compute.rules.node-key:a800-demo}}") String nodeKey,
                           @Value("${compute.runtime.demo-dataset:A800_DEMO_FIXTURE}") String demoDataset,
                           @Value("${compute.runtime.medical-apis-enabled:false}") boolean medicalApisEnabled,
                           @Value("${compute.queue.recheck-enabled:false}") boolean queueRecheckEnabled) {
        this.mode = parseMode(mode);
        this.nodeKey = requireText(nodeKey, "compute.runtime.node-key");
        this.demoDataset = requireText(demoDataset, "compute.runtime.demo-dataset");
        this.medicalApisEnabled = medicalApisEnabled;
        this.queueRecheckEnabled = queueRecheckEnabled;
        if (this.mode == Mode.REAL) {
            throw new IllegalStateException("compute.runtime.mode=REAL 已明确禁用：N-13 模拟节点与 N-16 真实采集尚未接入，"
                    + "真实模式不能启动；演示环境请保持 SIMULATION");
        }
        LOG.info("算力运行环境: mode={} nodeKey={} demoDataset={} medicalApis={} queueRecheck={}",
                this.mode, this.nodeKey, this.demoDataset,
                this.medicalApisEnabled ? "ENABLED" : "ISOLATED",
                this.queueRecheckEnabled ? "ENABLED" : "DISABLED");
    }

    static Mode parseMode(String value) {
        String normalized = value == null ? "SIMULATION" : value.trim().toUpperCase(Locale.ROOT);
        try {
            return Mode.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("compute.runtime.mode 只能是 SIMULATION 或 REAL，当前为 " + value, e);
        }
    }

    /** 返回命中的医疗入口前缀；不命中返回 null。按路径段边界匹配，避免误伤同名前缀接口。 */
    static String matchedIsolatedPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        for (String prefix : ISOLATED_MEDICAL_PATHS) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return prefix;
            }
        }
        return null;
    }

    Mode mode() {
        return mode;
    }

    boolean simulation() {
        return mode == Mode.SIMULATION;
    }

    String nodeKey() {
        return nodeKey;
    }

    String demoDataset() {
        return demoDataset;
    }

    boolean medicalApisEnabled() {
        return medicalApisEnabled;
    }

    boolean queueRecheckEnabled() {
        return queueRecheckEnabled;
    }

    List<String> isolatedMedicalPaths() {
        return ISOLATED_MEDICAL_PATHS;
    }

    private static String requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " 不能为空");
        }
        return value.trim();
    }
}
