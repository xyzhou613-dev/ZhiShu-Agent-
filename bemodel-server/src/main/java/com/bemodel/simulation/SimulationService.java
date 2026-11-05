package com.bemodel.simulation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.lab.LabSandboxService;
import com.bemodel.simulation.action.StockCutAction;
import com.bemodel.simulation.engine.CatalogSnapshot;
import com.bemodel.simulation.engine.PropagationEngine;
import com.bemodel.simulation.engine.PropagationStep;
import com.bemodel.simulation.entity.SimulationRun;
import com.bemodel.simulation.mapper.SimulationRunMapper;
import com.bemodel.simulation.observe.ObservationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 推演编排(spec §3):跑前 reset 防残留(含 lab ADVERSARIAL 污染)→ 观察前照 → 施加(记前值)
 * → 传导 → 观察后照 → diff → payload 完成时一次写 bm_simulation_run。
 * 边界:沙箱与 /lab 共享,单用户演示期不做并发互斥,同时跑两边会互踩。
 */
@Slf4j
@Service
public class SimulationService {

    private final SimulationScenarioRegistry scenarioRegistry;

    private final SimulationCatalogService catalogService;
    private final PropagationEngine engine;
    private final StockCutAction stockCutAction;
    private final ObservationService observationService;
    private final LabSandboxService sandboxService;
    private final DatasourceService datasourceService;
    private final SimulationRunMapper runMapper;
    private final ObjectMapper objectMapper;

    /** 单线程串行:与 lab 同款 daemon 执行器,进程死则 15min stale 兜底 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "simulation-run");
        t.setDaemon(true);
        return t;
    });

    public SimulationService(SimulationScenarioRegistry scenarioRegistry,
                             SimulationCatalogService catalogService, PropagationEngine engine,
                             StockCutAction stockCutAction, ObservationService observationService,
                             LabSandboxService sandboxService, DatasourceService datasourceService,
                             SimulationRunMapper runMapper, ObjectMapper objectMapper) {
        this.scenarioRegistry = scenarioRegistry;
        this.catalogService = catalogService;
        this.engine = engine;
        this.stockCutAction = stockCutAction;
        this.observationService = observationService;
        this.sandboxService = sandboxService;
        this.datasourceService = datasourceService;
        this.runMapper = runMapper;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> start(String scenarioKey, Map<String, String> params) {
        SimulationScenarioRegistry.Scenario scen = scenarioRegistry.get(scenarioKey);
        Map<String, String> p = params == null ? Map.of() : params;
        String drugCode = p.getOrDefault("drugCode", scen.paramDefaults().get("drugCode"));
        int quantity;
        try {
            quantity = Integer.parseInt(p.getOrDefault("quantity", scen.paramDefaults().get("quantity")));
        } catch (NumberFormatException e) {
            throw new BizException("库存数量须是整数: " + p.get("quantity"));
        }
        if (quantity < 0) {
            throw new BizException("库存数量不能为负数");
        }
        SimulationRun run = new SimulationRun();
        run.setScenario(scen.key());
        run.setStatus("QUEUED");
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);
        Long runId = run.getId();
        executor.submit(() -> execute(runId, scen, drugCode, quantity));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("status", "QUEUED");
        return out;
    }

    private void execute(Long runId, SimulationScenarioRegistry.Scenario scen,
                         String drugCode, int quantity) {
        long t0 = System.currentTimeMillis();
        try {
            patch(runId, "RUNNING", null, null, null);
            sandboxService.reset();
            JdbcTemplate lab = datasourceService.jdbc(LabSandboxService.DS_LAB);

            Map<String, Object> before = observationService.snapshot(lab, drugCode);
            StockCutAction.Applied applied = stockCutAction.apply(lab, drugCode, quantity);

            CatalogSnapshot catalog = catalogService.snapshot();
            LinkedHashMap<String, Set<String>> startKeys = new LinkedHashMap<>();
            startKeys.put(scen.keyFamily().get(0), new LinkedHashSet<>(List.of(drugCode)));
            List<PropagationStep> steps = engine.propagate(catalog, scen.startConcept(),
                    scen.keyFamily(), (sql, params) -> lab.queryForList(sql, params.toArray()), startKeys);

            Map<String, Object> after = observationService.snapshot(lab, drugCode);
            Map<String, Object> payload = buildPayload(scen, applied, drugCode, steps, before, after,
                    System.currentTimeMillis() - t0);
            patch(runId, "DONE", objectMapper.writeValueAsString(payload), null, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("推演失败: run={} - {}", runId, e.getMessage(), e);
            patch(runId, "FAILED", null, clip(String.valueOf(e.getMessage()), 1000), LocalDateTime.now());
        }
    }

    /** payload 组装唯一出口(spec §4 终局契约) */
    private Map<String, Object> buildPayload(SimulationScenarioRegistry.Scenario scen,
                                             StockCutAction.Applied applied, String drugCode,
                                             List<PropagationStep> steps,
                                             Map<String, Object> before, Map<String, Object> after,
                                             long elapsedMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scenario", scen.key());
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("drugCode", drugCode);
        action.put("quantity", applied.after());
        payload.put("action", action);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("table", applied.table());
        target.put("column", applied.column());
        target.put("keyColumn", applied.keyColumn());
        target.put("keyValue", applied.keyValue());
        target.put("before", applied.before());
        target.put("after", applied.after());
        payload.put("target", target);
        payload.put("affectedKeys", List.of(drugCode));
        payload.put("steps", steps);
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("before", before);
        observation.put("after", after);
        payload.put("observation", observation);
        payload.put("diff", diffOf(applied, before, after));
        payload.put("elapsedMs", elapsedMs);
        return payload;
    }

    /** diff 卡:说人话句子,确定性拼接,不经过任何模型 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> diffOf(StockCutAction.Applied applied,
                                       Map<String, Object> before, Map<String, Object> after) {
        List<String> items = new ArrayList<>();
        items.add("库存 " + applied.keyValue() + ": " + applied.before() + " → " + applied.after());
        List<Map<String, Object>> bm = (List<Map<String, Object>>) before.get("metrics");
        List<Map<String, Object>> am = (List<Map<String, Object>>) after.get("metrics");
        for (int i = 0; i < am.size() && i < bm.size(); i++) {
            Map<String, Object> b = bm.get(i);
            Map<String, Object> a = am.get(i);
            Object bv = b.get("value");
            Object av = a.get("value");
            if (av == null && bv == null) {
                continue;
            }
            String line = a.get("name") + ": " + bv + " → " + av;
            if (Boolean.TRUE.equals(a.get("alert")) && !Boolean.TRUE.equals(b.get("alert"))) {
                line += "(告警点亮)";
            }
            items.add(line);
        }
        Map<String, Object> br = (Map<String, Object>) before.get("rule");
        Map<String, Object> ar = (Map<String, Object>) after.get("rule");
        if (ar.get("ruleName") != null) {
            String line = ar.get("ruleName") + ": " + passWord(br.get("pass")) + " → " + passWord(ar.get("pass"));
            if (ar.get("detail") != null) {
                line += "(" + ar.get("detail") + ")";
            }
            items.add(line);
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("items", items);
        return diff;
    }

    private String passWord(Object pass) {
        return Boolean.TRUE.equals(pass) ? "通过" : Boolean.FALSE.equals(pass) ? "不符" : "无从核对";
    }

    /** 轮询:QUEUED/RUNNING 超 15 分钟视为中断(执行器随进程死,无兜底),诚实落 FAILED */
    public Map<String, Object> status(Long runId) {
        SimulationRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException("推演不存在: " + runId);
        }
        if (("QUEUED".equals(run.getStatus()) || "RUNNING".equals(run.getStatus()))
                && run.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(15))) {
            patch(run.getId(), "FAILED", null, "推演中断（服务重启）", LocalDateTime.now());
            run.setStatus("FAILED");
            run.setErrorMsg("推演中断（服务重启）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getId());
        out.put("scenario", run.getScenario());
        out.put("status", run.getStatus());
        out.put("errorMsg", run.getErrorMsg());
        out.put("createdAt", run.getCreatedAt());
        out.put("finishedAt", run.getFinishedAt());
        if (run.getPayloadJson() != null) {
            try {
                out.put("payload", objectMapper.readValue(run.getPayloadJson(),
                        new TypeReference<Map<String, Object>>() {
                        }));
            } catch (Exception e) {
                out.put("payload", null);
                out.put("errorMsg", "结果解析失败: " + e.getMessage());
            }
        }
        return out;
    }

    public List<Map<String, Object>> recent() {
        return runMapper.selectList(new LambdaQueryWrapper<SimulationRun>()
                        .orderByDesc(SimulationRun::getId).last("LIMIT 20")).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", r.getId());
            m.put("scenario", r.getScenario());
            m.put("status", r.getStatus());
            m.put("createdAt", r.getCreatedAt());
            m.put("finishedAt", r.getFinishedAt());
            return m;
        }).toList();
    }

    public Map<String, Object> reset() {
        return sandboxService.reset();
    }

    public List<Map<String, Object>> scenarios() {
        return scenarioRegistry.all().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.key());
            m.put("name", s.name());
            m.put("description", s.description());
            m.put("startConcept", s.startConcept());
            m.put("paramDefaults", s.paramDefaults());
            return m;
        }).toList();
    }

    private void patch(Long id, String status, String payloadJson, String errorMsg,
                       LocalDateTime finishedAt) {
        SimulationRun p = new SimulationRun();
        p.setId(id);
        p.setStatus(status);
        p.setPayloadJson(payloadJson);
        p.setErrorMsg(errorMsg);
        p.setFinishedAt(finishedAt);
        runMapper.updateById(p);
    }

    private String clip(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
