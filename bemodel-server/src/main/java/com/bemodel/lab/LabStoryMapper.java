package com.bemodel.lab;

import com.bemodel.lab.engine.ToolStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工具轨迹→故事条节点的确定性映射(零 LLM,spec 2026-09-18 §2 红线):
 * 只重组 runArm 落库臂 Map 的既有字段文本,不做任何生成;成败只用臂 status 与 anchorsCited(既有锚点判读产物)。
 * 输入约定:steps 元素键 kind/tool/argsJson/resultJson/llmRaw,其中 argsJson/resultJson 为 JSON 字符串。
 */
public final class LabStoryMapper {

    /** 说人话动词字典(spec §3);execute_sql 单独按 args 是否含写关键字分「直接改库/直接查库」 */
    private static final Map<String, String> VERB = Map.of(
            "qc_check", "查规则依据",
            "drug_dict", "查药品字典",
            "stock_read", "看库存",
            "fee_gap_scan", "扫费用缺口",
            "action_list", "看能做什么动作",
            "exec_refund", "发起正规退费",
            "staff_trace", "顺着关系查人");

    /** 写库判别:与前端 wroteSandbox 同一正则口径(大小写不敏感) */
    private static final Pattern WRITE_SQL = Pattern.compile("update|insert|delete|drop", Pattern.CASE_INSENSITIVE);

    /** 诚实终态文案:与前端 ARM_STATUS 标签逐字一致(spec §3「文案沿用四诚实终态」) */
    private static final Map<String, String> HONEST_LABEL = Map.of(
            "FORMAT_FAILED", "AI 答非约定格式,已停止",
            "BUDGET_CUT", "超步数/时间,如实停止",
            "LLM_UNAVAILABLE", "AI 服务不可用");

    private static final ObjectMapper JSON = new ObjectMapper();

    private LabStoryMapper() {
    }

    /** 臂 Map→节点数组:start + 归并后的 TOOL 步节点 + result 终局节点(C 臂不进本映射,前端固定两节点) */
    public static List<Map<String, Object>> story(Map<String, Object> arm, List<String> anchorKeys, boolean free) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("start", "接到问题", "", "neutral", 1, false, List.of()));
        String status = String.valueOf(arm.get("status"));
        List<Map<String, Object>> steps = stepsOf(arm);
        for (Map<String, Object> s : steps) {
            if (!"DONE".equals(status) || !ToolStep.TOOL.equals(s.get("kind"))) {
                continue; // 非 DONE 只收单个 honest 终局节点(spec §3),不渲染动作步;FINAL/FORMAT_ERROR/熔断步不是动作
            }
            String tool = s.get("tool") == null ? "" : String.valueOf(s.get("tool"));
            String argsJson = s.get("argsJson") == null ? "" : String.valueOf(s.get("argsJson"));
            String resultJson = s.get("resultJson") == null ? "" : String.valueOf(s.get("resultJson"));
            String label = labelOf(tool, argsJson);
            String tone = toneOf(tool, argsJson, resultJson, anchorKeys, free);
            Map<String, Object> last = nodes.get(nodes.size() - 1);
            if ("step".equals(last.get("phase")) && label.equals(last.get("label")) && tone.equals(last.get("tone"))) {
                int count = ((Number) last.get("count")).intValue() + 1;
                last.put("count", count);
                last.put("expandable", count > 1);
                ((List<String>) last.get("members")).add("第" + count + "次：" + detailOf(argsJson));
            } else {
                nodes.add(node("step", label, detailOf(argsJson), tone, 1, false,
                        new ArrayList<>(List.of("第1次：" + detailOf(argsJson)))));
            }
        }
        nodes.add(resultNode(status, anchorsCited(arm), free));
        return nodes;
    }

    private static List<Map<String, Object>> stepsOf(Map<String, Object> arm) {
        Object v = arm.get("steps");
        if (v instanceof List) {
            return (List<Map<String, Object>>) v;
        }
        return List.of();
    }

    /** tone 优先级:FREE 全 neutral(无锚点键,FREE 用 execute_sql 作答是正当路径非违规) > execute_sql=violation(非 FREE 语境,spec §3) > 锚点键 contains 命中=key(复用既有判读口径) > neutral */
    private static String toneOf(String tool, String argsJson, String resultJson, List<String> anchorKeys, boolean free) {
        if (free) {
            return "neutral";
        }
        if ("execute_sql".equals(tool)) {
            return "violation";
        }
        String trace = tool + " " + argsJson + " " + resultJson;
        for (String k : anchorKeys) {
            if (trace.contains(k)) {
                return "key";
            }
        }
        return "neutral";
    }

    private static String labelOf(String tool, String argsJson) {
        if ("execute_sql".equals(tool)) {
            return WRITE_SQL.matcher(argsJson).find() ? "直接改库" : "直接查库";
        }
        return VERB.getOrDefault(tool, tool.isEmpty() ? "执行操作" : tool);
    }

    /** detail=argsJson 前两个键值对「k=v，k=v」;非 JSON 或空对象取原文截 60 字。只摘抄不生成 */
    private static String detailOf(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "";
        }
        try {
            JsonNode tree = JSON.readTree(argsJson);
            if (tree.isObject() && tree.size() == 0) {
                return "（无参数）";
            }
            if (tree.isObject()) {
                StringBuilder sb = new StringBuilder();
                Iterator<Map.Entry<String, JsonNode>> it = tree.fields();
                int pairs = 0;
                while (it.hasNext() && pairs < 2) { // 只取前 2 个键值对,防超长 args 撑爆标签
                    Map.Entry<String, JsonNode> e = it.next();
                    String v = e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString();
                    if (pairs > 0) {
                        sb.append("，");
                    }
                    sb.append(e.getKey()).append('=').append(clip(v, 40));
                    pairs++;
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        } catch (Exception ignore) {
            // 落回原文截断
        }
        return clip(argsJson, 60);
    }

    /** 终局节点:成败只由臂 status+anchorsCited+free 决定(既有判读产物,零新增判断) */
    private static Map<String, Object> resultNode(String status, List<String> anchors, boolean free) {
        if ("DONE".equals(status)) {
            if (!anchors.isEmpty()) {
                return node("result", "结论有规则依据背书", "引用规则依据：" + String.join("、", anchors),
                        "success", 1, false, List.of());
            }
            if (free) {
                return node("result", "已作答", "自由提问,无预设规则可判", "neutral", 1, false, List.of());
            }
            return node("result", "已作答,全程未用规则依据", "", "neutral", 1, false, List.of());
        }
        String label = HONEST_LABEL.getOrDefault(status, "已停止(" + status + ")");
        return node("result", label, "如实停止,不硬凑结论", "honest", 1, false, List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<String> anchorsCited(Map<String, Object> arm) {
        Object v = arm.get("anchorsCited");
        if (v instanceof List) {
            return ((List<Object>) v).stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Map<String, Object> node(String phase, String label, String detail, String tone,
                                            int count, boolean expandable, List<String> members) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("phase", phase);
        n.put("label", label);
        n.put("detail", detail);
        n.put("tone", tone);
        n.put("count", count);
        n.put("expandable", expandable);
        n.put("members", members);
        return n;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
