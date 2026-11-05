package com.bemodel.lab;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.lab.LabExperimentRegistry.LabExperiment;
import com.bemodel.lab.engine.LabTool;
import com.bemodel.lab.engine.ToolLoopResult;
import com.bemodel.lab.engine.ToolLoopRunner;
import com.bemodel.lab.engine.ToolStep;
import com.bemodel.lab.entity.LabRun;
import com.bemodel.lab.mapper.LabRunMapper;
import com.bemodel.lab.tools.BareSqlTool;
import com.bemodel.lab.tools.SandboxTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LabRun 异步编排:POST 起跑 → 单线程执行器串行跑三臂(共享沙箱,顺序执行避免写交织;
 * E2/E3 的 B 臂写操作与 A 臂读操作不并发)→ 轨迹/锚点判读/耗时整体落 bm_lab_run.payload_json。
 * 四态诚实终态由 ToolLoopResult 承接;整体异常落 FAILED+原因,不伪装结论。
 * 锚点判读只认工具轨迹(tool/args/result 文本),LLM 自述不作数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabRunService {

    private final LabProps props;
    private final LabSandboxService sandboxService;
    private final SandboxTools sandboxTools;
    private final BareSqlTool bareSqlTool;
    private final ToolLoopRunner loopRunner;
    private final LabExperimentRegistry registry;
    private final LabAuditService auditService;
    private final LabRunMapper runMapper;
    private final ObjectMapper objectMapper;

    /** 单线程串行:沙箱是共享写面,并行臂会写交织;演示规模下串行延迟可接受 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lab-run");
        t.setDaemon(true);
        return t;
    });

    /** 起跑:问题原文与预设剧本完全一致走剧本(含锚点判读),否则自由提问;ensureCloned 放异步首步,不拖住 HTTP 请求 */
    public Map<String, Object> start(String experimentKey, String question, Boolean forceExecute) {
        String q = question == null ? "" : question.trim();
        if (q.length() > 500) {
            throw new BizException("问题太长(上限 500 字)");
        }
        LabExperiment exp;
        if (!q.isEmpty()) {
            exp = registry.resolve(q);
        } else {
            if (experimentKey == null || experimentKey.isBlank()) {
                throw new BizException("请输入问题");
            }
            exp = registry.get(experimentKey);
        }
        LabRun run = new LabRun();
        run.setExperimentKey(exp.key());
        run.setQuestion(exp.question());
        run.setForceExecute(forceExecute == null || forceExecute);
        run.setStatus("QUEUED");
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);
        Long runId = run.getId();
        executor.submit(() -> execute(runId, exp, run.getForceExecute()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("status", "QUEUED");
        return out;
    }

    private void execute(Long runId, LabExperiment exp, boolean forceExecute) {
        long t0 = System.currentTimeMillis();
        try {
            patch(runId, "RUNNING", null, null, null);
            sandboxService.ensureCloned();

            Map<String, Object> armA = runArm("A", exp, "LAB_A", exp.aPrompt(),
                    sandboxTools.forExperiment(exp.key()), props.getMaxStepsA());
            // 臂级增量落库:A 完成即写,前端 2s 轮询立刻能看到先完成臂的故事条(实时点亮)
            patchPartial(runId, exp, forceExecute, t0, armA, null);
            String bPrompt = forceExecute ? exp.bPromptForced() : exp.bPromptGentle();
            Map<String, Object> armB = runArm("B", exp, "LAB_B", bPrompt,
                    List.of(bareSqlTool), props.getMaxStepsB());
            patchPartial(runId, exp, forceExecute, t0, armA, armB);
            Map<String, Object> armC = new LinkedHashMap<>();
            armC.put("arm", "C");
            armC.put("status", "DONE");
            armC.put("answer", exp.cAnswer());
            armC.put("steps", List.of());
            armC.put("llmCalls", 0);
            armC.put("elapsedMs", 0);
            armC.put("anchorsCited", List.of());

            Map<String, Object> payload = buildPayload(exp, forceExecute, t0, armA, armB, armC);
            patch(runId, "DONE", objectMapper.writeValueAsString(payload), null, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("Lab 运行失败: run={} - {}", runId, e.getMessage(), e);
            patch(runId, "FAILED", null, clip(String.valueOf(e.getMessage()), 1000), LocalDateTime.now());
        }
    }

    /** payload 组装唯一出口(增量与终局同构,形状契约不变);armX 传 null=该臂尚未完成,不进 arms */
    private Map<String, Object> buildPayload(LabExperiment exp, boolean forceExecute, long t0,
                                             Map<String, Object> armA, Map<String, Object> armB, Map<String, Object> armC) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> experiment = new LinkedHashMap<>();
        experiment.put("key", exp.key());
        experiment.put("title", exp.title());
        experiment.put("question", exp.question());
        payload.put("experiment", experiment);
        payload.put("forceExecute", forceExecute);
        Map<String, Object> arms = new LinkedHashMap<>();
        if (armA != null) {
            arms.put("A", armA);
        }
        if (armB != null) {
            arms.put("B", armB);
        }
        if (armC != null) {
            arms.put("C", armC);
        }
        payload.put("arms", arms);
        payload.put("elapsedMs", System.currentTimeMillis() - t0);
        return payload;
    }

    /** 运行中增量写 payload(状态仍 RUNNING);失败只记日志不拖垮终局 */
    private void patchPartial(Long runId, LabExperiment exp, boolean forceExecute, long t0,
                              Map<String, Object> armA, Map<String, Object> armB) {
        try {
            patch(runId, "RUNNING",
                    objectMapper.writeValueAsString(buildPayload(exp, forceExecute, t0, armA, armB, null)),
                    null, null);
        } catch (Exception e) {
            log.warn("Lab 增量落库失败(不影响终局): run={} - {}", runId, e.getMessage());
        }
    }

    /** 单臂执行 + 轨迹序列化(截断防膨胀) + 锚点判读 */
    private Map<String, Object> runArm(String arm, LabExperiment exp, String callType,
                                       String rolePrompt, List<LabTool> tools, int maxSteps) {
        long t0 = System.currentTimeMillis();
        ToolLoopResult r = loopRunner.run(callType, rolePrompt, exp.question(), tools,
                maxSteps, props.getArmBudgetSeconds());
        List<Map<String, Object>> steps = new ArrayList<>();
        StringBuilder traceText = new StringBuilder();
        for (ToolStep s : r.getSteps()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", s.getKind());
            m.put("tool", s.getTool());
            m.put("argsJson", s.getArgsJson());
            m.put("resultJson", clip(s.getResultJson(), 1500));
            m.put("llmRaw", clip(s.getLlmRaw(), 500));
            steps.add(m);
            // 锚点只认工具轨迹:FINAL 步的 resultJson 是 LLM 自述答案,计入即等于让模型自称的锚点作数
            if (ToolStep.TOOL.equals(s.getKind())) {
                traceText.append(s.getTool() == null ? "" : s.getTool()).append(' ')
                        .append(s.getArgsJson() == null ? "" : s.getArgsJson()).append(' ')
                        .append(s.getResultJson() == null ? "" : s.getResultJson()).append(' ');
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arm", arm);
        out.put("status", r.getStatus().name());
        out.put("answer", r.getAnswer());
        out.put("steps", steps);
        out.put("llmCalls", r.getLlmCalls());
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        out.put("anchorsCited", exp.anchorKeys().stream()
                .filter(k -> traceText.toString().contains(k)).toList());
        return out;
    }

    /** 轮询:QUEUED/RUNNING 超 15 分钟视为中断(执行器随进程死,无兜底),诚实落 FAILED */
    public Map<String, Object> status(Long runId) {
        LabRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException("运行不存在: " + runId);
        }
        if (("QUEUED".equals(run.getStatus()) || "RUNNING".equals(run.getStatus()))
                && run.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(15))) {
            patch(run.getId(), "FAILED", null, "运行中断（服务重启）", LocalDateTime.now());
            run.setStatus("FAILED");
            run.setErrorMsg("运行中断（服务重启）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getId());
        out.put("experimentKey", run.getExperimentKey());
        out.put("question", run.getQuestion());
        out.put("forceExecute", run.getForceExecute());
        out.put("status", run.getStatus());
        out.put("errorMsg", run.getErrorMsg());
        out.put("createdAt", run.getCreatedAt());
        out.put("finishedAt", run.getFinishedAt());
        if (run.getPayloadJson() != null) {
            try {
                Map<String, Object> payload = objectMapper.readValue(run.getPayloadJson(),
                        new TypeReference<Map<String, Object>>() {
                        });
                out.put("payload", payload);
                attachStories(out, run, payload);
            } catch (Exception e) {
                out.put("payload", null);
                out.put("errorMsg", "结果解析失败: " + e.getMessage());
            }
        }
        return out;
    }

    /** 最近运行(前端跑批列表) */
    public List<Map<String, Object>> recent() {
        return runMapper.selectList(new LambdaQueryWrapper<LabRun>()
                        .orderByDesc(LabRun::getId).last("LIMIT 20")).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", r.getId());
            m.put("experimentKey", r.getExperimentKey());
            m.put("status", r.getStatus());
            m.put("createdAt", r.getCreatedAt());
            m.put("finishedAt", r.getFinishedAt());
            return m;
        }).toList();
    }

    public Map<String, Object> reset() {
        return sandboxService.reset();
    }

    public List<Map<String, Object>> experiments() {
        return registry.summaries();
    }

    public Map<String, Object> audit() {
        return auditService.audit();
    }

    private void patch(Long id, String status, String payloadJson, String errorMsg, LocalDateTime finishedAt) {
        LabRun p = new LabRun();
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

    /** 故事条=确定性映射(spec §2 红线,零 LLM);映射失败只记日志,绝不影响运行详情主链路 */
    @SuppressWarnings("unchecked")
    private void attachStories(Map<String, Object> out, LabRun run, Map<String, Object> payload) {
        try {
            Object armsObj = payload.get("arms");
            if (!(armsObj instanceof Map)) {
                return;
            }
            Map<String, Object> arms = (Map<String, Object>) armsObj;
            boolean free = LabExperimentRegistry.FREE_KEY.equals(run.getExperimentKey());
            // FREE 不在注册表(get 会抛),且无锚点键;mapper 的 free=true 亦短路锚点判定,双保险
            List<String> anchorKeys = free ? List.of() : registry.get(run.getExperimentKey()).anchorKeys();
            Object a = arms.get("A");
            if (a instanceof Map) {
                out.put("storyA", LabStoryMapper.story((Map<String, Object>) a, anchorKeys, free));
            }
            Object b = arms.get("B");
            if (b instanceof Map) {
                out.put("storyB", LabStoryMapper.story((Map<String, Object>) b, anchorKeys, free));
            }
        } catch (Exception e) {
            log.warn("故事条映射失败(不影响运行详情): run={} - {}", run.getId(), e.getMessage());
        }
    }
}
