package com.bemodel.lab.engine;

import com.bemodel.llm.DeepSeekClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON 协议工具循环(Plan 1):在 DeepSeekClient 单轮文本口上跑多步工具调用,
 * 不动 DeepSeekClient、不押注 function calling(评审定稿:JSON 协议为 v1 主体,tools 字段为预留升级路径)。
 *
 * 每轮把「工具清单 + 全部转写史」作为单轮上下文喂给模型,期望只回一个 JSON:
 *   {"action":"tool","tool":"工具名","args":{...}}  或  {"action":"final","answer":"结论"}
 * 兜底:输出不合协议 → 一次纠错回喂;再犯 → FORMAT_FAILED(部分轨迹如实呈现);
 * 步数/墙钟熔断 → BUDGET_CUT;LLM 不可用 → LLM_UNAVAILABLE。全部终局不伪装结论。
 */
@Slf4j
@Component
public class ToolLoopRunner {

    private static final String PROTOCOL = """

            # 输出协议(必须严格遵守)
            每轮只输出一个 JSON 对象,禁止输出任何其他文字或 markdown 围栏:
            调用工具:{"action":"tool","tool":"工具名","args":{...}}
            给出结论:{"action":"final","answer":"结论文字"}
            answer 只能组织工具返回里已有的事实,结果里没有的数字不要编造。""";

    private final DeepSeekClient llm;
    private final ObjectMapper objectMapper;

    public ToolLoopRunner(DeepSeekClient llm, ObjectMapper objectMapper) {
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    public ToolLoopResult run(String callType, String rolePrompt, String question,
                              List<LabTool> tools, int maxSteps, int budgetSeconds) {
        long start = System.currentTimeMillis();
        ToolLoopResult out = new ToolLoopResult();
        List<ToolStep> steps = out.getSteps();
        String sys = rolePrompt + "\n\n# 可用工具\n" + manifest(tools) + "\n" + PROTOCOL;
        StringBuilder transcript = new StringBuilder("用户问题:").append(question);
        int formatRepairs = 0;
        long deadline = start + budgetSeconds * 1000L;

        while (true) {
            if (steps.size() >= maxSteps) {
                return finish(out, ToolLoopResult.Status.BUDGET_CUT, start,
                        ToolStep.STEP_LIMIT, "达到步数上限 " + maxSteps);
            }
            if (System.currentTimeMillis() > deadline) {
                return finish(out, ToolLoopResult.Status.BUDGET_CUT, start,
                        ToolStep.BUDGET_CUT, "达到墙钟预算 " + budgetSeconds + "s");
            }
            Optional<String> resp = llm.chat(callType, sys, transcript.toString());
            out.setLlmCalls(out.getLlmCalls() + 1);
            if (resp.isEmpty()) {
                return finish(out, ToolLoopResult.Status.LLM_UNAVAILABLE, start, null, null);
            }
            JsonNode decision = parseDecision(resp.get());
            if (decision == null) {
                ToolStep bad = new ToolStep();
                bad.setKind(ToolStep.FORMAT_ERROR);
                bad.setLlmRaw(resp.get());
                steps.add(bad);
                if (formatRepairs++ == 0) {
                    transcript.append("\n助手输出(不符合协议):").append(resp.get())
                            .append("\n系统纠正:只输出一个 JSON 对象,见输出协议。");
                    continue;
                }
                return finish(out, ToolLoopResult.Status.FORMAT_FAILED, start, null, null);
            }
            if ("final".equals(decision.path("action").asText())) {
                ToolStep fin = new ToolStep();
                fin.setKind(ToolStep.FINAL);
                fin.setLlmRaw(resp.get());
                fin.setResultJson(decision.path("answer").asText(""));
                steps.add(fin);
                out.setStatus(ToolLoopResult.Status.DONE);
                out.setAnswer(decision.path("answer").asText());
                out.setElapsedMs(System.currentTimeMillis() - start);
                return out;
            }
            String toolName = decision.path("tool").asText("");
            LabTool tool = tools.stream().filter(t -> t.name().equals(toolName)).findFirst().orElse(null);
            String resultJson;
            if (tool == null) {
                resultJson = objectMapper.valueToTree(Map.of("error", "工具不存在,可用工具:"
                        + tools.stream().map(LabTool::name).toList())).toString();
            } else {
                try {
                    resultJson = objectMapper.valueToTree(tool.execute(decision.path("args"))).toString();
                } catch (Exception e) {
                    log.warn("Lab 工具执行失败: {} - {}", toolName, e.getMessage());
                    resultJson = objectMapper.valueToTree(Map.of("error", String.valueOf(e.getMessage()))).toString();
                }
            }
            ToolStep step = new ToolStep();
            step.setKind(ToolStep.TOOL);
            step.setTool(toolName);
            step.setArgsJson(decision.path("args").toString());
            step.setResultJson(resultJson);
            step.setLlmRaw(resp.get());
            steps.add(step);
            transcript.append("\n助手调用 ").append(toolName).append("(").append(step.getArgsJson()).append(")")
                    .append("\n工具返回:").append(resultJson)
                    .append("\n(继续:输出 tool 或 final JSON)");
        }
    }

    private ToolLoopResult finish(ToolLoopResult out, ToolLoopResult.Status status, long start,
                                  String cutKind, String cutNote) {
        if (cutKind != null) {
            ToolStep cut = new ToolStep();
            cut.setKind(cutKind);
            cut.setResultJson(cutNote);
            out.getSteps().add(cut);
        }
        out.setStatus(status);
        out.setElapsedMs(System.currentTimeMillis() - start);
        return out;
    }

    /** 宽容解析:剥 markdown 围栏 + 结构校验(action/tool/answer 形状),不合规返回 null。包级可见供单测 */
    JsonNode parseDecision(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            s = (nl < 0 ? "" : s.substring(nl + 1)).trim();
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3).trim();
            }
        }
        try {
            JsonNode n = objectMapper.readTree(s);
            if (!n.isObject()) {
                return null;
            }
            String action = n.path("action").asText("");
            if ("final".equals(action)) {
                return n.path("answer").asText("").isBlank() ? null : n;
            }
            if ("tool".equals(action)) {
                return n.path("tool").asText("").isBlank() ? null : n;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String manifest(List<LabTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (LabTool t : tools) {
            sb.append("- name: ").append(t.name())
                    .append("\n  说明: ").append(t.description())
                    .append("\n  参数: ").append(t.argsSchema()).append('\n');
        }
        return sb.toString();
    }
}
