package com.bemodel.mcp;

import com.bemodel.common.BizException;
import com.bemodel.cs.CsService;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.service.MetricService;
import com.bemodel.search.SearchService;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * MCP 只读工具的业务面(spec 借鉴 1「查询即应用」):
 * 定位与渲染是纯逻辑(本类可单测);协议装配(toolSpec/handler)在本类尾部,SDK 类型只在协议段出现。
 * 只读红线:只调 CsService.ask / SearchService.search / MetricService.getByCode+evaluate,
 * 既有 SQL 白名单+只读校验+脱敏+bm_llm_log 审计全在原链路内,零改动。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BemodelMcpTools {

    private final CsService csService;
    private final SearchService searchService;
    private final MetricService metricService;

    /** 按编码或名称定位指标:编码直命中优先,否则走语义搜索取 type=指标 的命中;定位不到=语义性输入错误(抛 BizException,由协议层转 isError:true 供模型自纠) */
    String resolveMetricCode(String metric) {
        String key = metric == null ? "" : metric.trim();
        if (key.isEmpty()) {
            throw new BizException("请告诉我要查口径的指标名称或编码");
        }
        Metric byCode = metricService.getByCode(key);
        if (byCode != null) {
            return byCode.getMetricCode();
        }
        Map<String, Object> search = searchService.search(key, false);
        Object hitsObj = search.get("hits");
        if (hitsObj instanceof List<?> hits) {
            for (Object o : hits) {
                if (o instanceof Map<?, ?> h && "指标".equals(h.get("type")) && h.get("metricCode") != null) {
                    return String.valueOf(h.get("metricCode"));
                }
            }
        }
        throw new BizException("没有找到叫「" + key + "」的指标，可以先问数据问题试试");
    }

    /** 问数结果→文本:答案正文 + 「证据」小节(label/value 逐条) + 追溯编号;口径卡/能力菜单原样透出,不做改写 */
    String renderAsk(Map<String, Object> result) {
        StringBuilder sb = new StringBuilder();
        Object answer = result.get("answer");
        sb.append(answer == null ? "" : String.valueOf(answer));
        Object evidence = result.get("evidence");
        if (evidence instanceof List<?> evList && !evList.isEmpty()) {
            sb.append("\n\n证据：");
            for (Object o : evList) {
                if (o instanceof Map<?, ?> ev) {
                    sb.append("\n· ").append(ev.get("label")).append("：").append(ev.get("value"));
                }
            }
        }
        Object metric = result.get("metric");
        if (metric instanceof Map<?, ?> card) {
            sb.append("\n\n—— 口径卡 ——");
            appendIfPresent(sb, "口径定义", card.get("definition"));
            appendIfPresent(sb, "计算公式", card.get("formula"));
            appendIfPresent(sb, "探针 SQL", card.get("probeSql"));
            appendIfPresent(sb, "数据源", card.get("dsCode"));
            appendIfPresent(sb, "负责人", card.get("owner"));
            appendIfPresent(sb, "预警阈值", card.get("warnThreshold"));
            appendIfPresent(sb, "最近实测", card.get("lastVal"));
        }
        Object traceId = result.get("traceId");
        if (traceId != null && !String.valueOf(traceId).isEmpty()) {
            sb.append("\n\n追溯编号：").append(traceId);
        }
        return sb.toString();
    }

    /** 口径卡→文本;eval 传 null=未绑定探针(如实注明,不编造实测值) */
    String renderMetricCard(Metric m, Map<String, Object> eval) {
        StringBuilder sb = new StringBuilder();
        sb.append("【口径卡】").append(m.getName()).append("（编码 ").append(m.getMetricCode()).append("）");
        appendIfPresent(sb, "口径定义", m.getDefinition());
        appendIfPresent(sb, "计算公式", m.getFormula());
        appendIfPresent(sb, "探针 SQL", m.getProbeSql());
        appendIfPresent(sb, "数据源", m.getDsCode());
        appendIfPresent(sb, "负责人", m.getOwner());
        appendIfPresent(sb, "预警阈值", m.getWarnThreshold());
        if (eval == null) {
            sb.append("\n· 最近实测：该指标未绑定巡检探针（或缺数据源），暂无实测值");
        } else {
            sb.append("\n· 最近实测：").append(eval.get("value"))
                    .append(Boolean.TRUE.equals(eval.get("alarm")) ? "（已超预警阈值）" : "（正常）");
            if (eval.get("evaluatedAt") instanceof LocalDateTime at) {
                sb.append("\n· 实测时间：").append(at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            }
        }
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String label, Object v) {
        if (v != null && !String.valueOf(v).isEmpty()) {
            sb.append("\n· ").append(label).append("：").append(v);
        }
    }

    // ---------- 协议装配:工具名/schema/描述说人话;只读两件,鉴权按演示期拍板为无(spec §7) ----------

    /** 只读工具一:语义问数。业务异常=BizException→isError:true(模型可自纠);其余异常=脱敏概要;LLM 缺位走既有降级,不抛错 */
    public McpStatelessServerFeatures.SyncToolSpecification askDataQuestion() {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "question", Map.of("type", "string", "description", "用中文描述要查的数据问题")),
                "required", List.of("question"));
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(Tool.builder("ask_data_question", inputSchema)
                        .title("语义问数")
                        .description("向 bemodel 语义层提一个数据问题，返回自然语言答案与证据（执行的 SQL、数据源、结果行数、证据链编号）。只读，不改任何数据。")
                        .annotations(ToolAnnotations.builder().title("只读查询").readOnlyHint(true).build())
                        .build())
                .callHandler((exchange, request) -> execute(() -> {
                    String question = String.valueOf(request.arguments().getOrDefault("question", "")).trim();
                    if (question.isEmpty()) {
                        throw new BizException("问题不能为空");
                    }
                    return renderAsk(csService.ask(question, "ANALYTICS"));
                }))
                .build();
    }

    /** 只读工具二:指标口径卡。查无指标→isError:true;未绑探针→照常出卡并如实注明(降级是业务结果,spec §5) */
    public McpStatelessServerFeatures.SyncToolSpecification getMetricCard() {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "metric", Map.of("type", "string", "description", "指标名称或编码，如 出院人数")),
                "required", List.of("metric"));
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(Tool.builder("get_metric_card", inputSchema)
                        .title("指标口径卡")
                        .description("按指标名称或编码查统一口径卡：指标定义、计算公式、探针 SQL、巡检实测值与告警状态。只读。")
                        .annotations(ToolAnnotations.builder().title("只读查询").readOnlyHint(true).build())
                        .build())
                .callHandler((exchange, request) -> execute(() -> {
                    String code = resolveMetricCode(String.valueOf(request.arguments().getOrDefault("metric", "")));
                    Metric m = metricService.getByCode(code);
                    Map<String, Object> eval;
                    try {
                        // evaluate 会回写 bm_metric 巡检元数据(last_val/last_eval_at)——「只读」指查询口径,存储侧有元数据回写,鉴权升级时一并评估
                        eval = metricService.evaluate(code);
                    } catch (BizException probeMiss) {
                        eval = null; // 未绑探针/数据源:口径卡仍可用,文本如实注明无实测值
                    }
                    return renderMetricCard(m, eval);
                }))
                .build();
    }

    /** 统一出口:成功=isError:false;业务异常=原文(模型可读);内部异常=只给异常类名,不泄栈(spec §5 安全基线) */
    private CallToolResult execute(java.util.concurrent.Callable<String> action) {
        try {
            return CallToolResult.builder().addTextContent(action.call()).isError(false).build();
        } catch (BizException e) {
            return CallToolResult.builder().addTextContent(e.getMessage()).isError(true).build();
        } catch (Exception e) {
            log.warn("MCP 工具执行失败: {}", e.getMessage(), e);
            return CallToolResult.builder()
                    .addTextContent("系统异常：" + e.getClass().getSimpleName()).isError(true).build();
        }
    }
}
