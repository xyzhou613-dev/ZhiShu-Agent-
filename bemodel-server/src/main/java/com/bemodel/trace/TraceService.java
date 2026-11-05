package com.bemodel.trace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.cs.QaTrace;
import com.bemodel.cs.mapper.QaTraceMapper;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.entity.MappingLog;
import com.bemodel.datasource.mapper.MappingLogMapper;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.ontology.entity.Attribute;
import com.bemodel.ontology.entity.Concept;
import com.bemodel.ontology.entity.OntologyMiss;
import com.bemodel.ontology.mapper.AttributeMapper;
import com.bemodel.ontology.mapper.ConceptMapper;
import com.bemodel.ontology.mapper.OntologyMissMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据链 trace 聚合（D3）：把分散在多张表里的证据按「答案五段式」组装为可逐段还原的链——
 * 结论(summary) → 排查路径(spans) → 证据锚点(anchors) → 建议动作(actions) → 参考文件(refs)。
 *
 * 硬约束（drugmodel 前车之鉴）：
 * - 无锚点不输出：anchors 结构性保证非空（被查对象自身的平台页就是最后一跳）；
 * - 锚点/参考文件必须平台内可达：route 一律平台内路由，绝不指向站外相对路径；
 * - 数字与结论全部来自落库事实，聚合器不编造（审批人/操作人/耗时都是既有字段）。
 */
@Service
@RequiredArgsConstructor
public class TraceService {

    private final QaTraceMapper qaTraceMapper;
    private final MappingMapper mappingMapper;
    private final MappingLogMapper mappingLogMapper;
    private final ConceptMapper conceptMapper;
    private final AttributeMapper attributeMapper;
    private final OntologyMissMapper missMapper;

    /** type=QA（问数答案）| MAPPING（映射生命周期）| CONCEPT（概念全景）；key=traceId/映射id/概念code */
    public Map<String, Object> assemble(String type, String key) {
        return switch (type == null ? "" : type.toUpperCase()) {
            case "QA" -> qa(key);
            case "MAPPING" -> mapping(mappingKey(key));
            case "CONCEPT" -> concept(key);
            default -> throw new BizException("未知证据链类型: " + type + "（支持 QA/MAPPING/CONCEPT）");
        };
    }

    /** 映射证据链的 key 必须是数字 id：显式报错，不落 NumberFormatException→500 兜底 */
    private static long mappingKey(String key) {
        try {
            return Long.parseLong(key == null ? "" : key.trim());
        } catch (NumberFormatException e) {
            throw new BizException("映射证据链的 key 须为映射 id（数字）: " + key);
        }
    }

    // ---------- QA：问题→语义计划→安全校验→真实执行→作答 ----------

    private Map<String, Object> qa(String traceId) {
        QaTrace t = qaTraceMapper.selectOne(new LambdaQueryWrapper<QaTrace>()
                .eq(QaTrace::getTraceId, traceId).last("LIMIT 1"));
        if (t == null) {
            throw new BizException("证据链不存在: " + traceId);
        }
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span(1, "提问", t.getQuestion() + "（场景 " + t.getScene() + "）", null, t.getCreatedAt()));
        spans.add(span(2, "语义计划", "命中概念 [" + nn(t.getMatchedConcepts()) + "]；"
                + nn(t.getSemantics()), null, null));
        spans.add(span(3, "安全校验", "SQL 经只读校验 + 表/列白名单 + LIMIT 钳制后才可执行", null, null));
        spans.add(span(4, "真实执行", "数据源 " + nn(t.getDsCode()) + "，物理表 [" + nn(t.getUsedTables())
                + "]，返回 " + t.getRowCount() + " 行，耗时 " + t.getDurationMs() + "ms；执行 SQL：" + nn(t.getSqlText()),
                null, null));
        spans.add(span(5, "结论作答", "作答来源 " + t.getAnswerSource() + "（LLM 只表达，数字全部来自真实查询）",
                null, null));

        List<String> matched = codes(t.getMatchedConcepts());
        List<Map<String, Object>> anchors = new ArrayList<>();
        for (String code : matched) {
            anchors.add(anchor("概念 " + code, "/ontology?concept=" + code));
        }
        if (!codes(t.getUsedTables()).isEmpty()) {
            anchors.add(anchor("数据源绑定（物理表映射）", "/datasource"));
        }
        if (!matched.isEmpty()) {
            anchors.add(anchor("链路追溯（概念生命周期影响）", "/link?concept=" + matched.get(0)));
        }

        List<Map<String, Object>> refs = new ArrayList<>();
        refs.add(anchor("本体管理（命中概念定义）",
                matched.isEmpty() ? "/ontology" : "/ontology?concept=" + matched.get(0)));

        return base("QA", t.getTraceId(),
                t.getAnswerSource() + " 作答（" + t.getRowCount() + " 行真实查询，耗时 " + t.getDurationMs() + "ms）："
                        + truncate(t.getAnswer(), 160),
                spans, anchors,
                List.of("在数据源绑定页核对该表映射仍为生效（ACTIVE）——提议映射不进运行时",
                        "对同一问题复问可对比 traceId，验证口径是否漂移"),
                refs);
    }

    // ---------- MAPPING：创建→提议/生效→变更留痕（与评审员/建模员角色互相成全） ----------

    private Map<String, Object> mapping(Long id) {
        Mapping m = mappingMapper.selectById(id);
        if (m == null) {
            throw new BizException("映射不存在: " + id);
        }
        List<MappingLog> logs = mappingLogMapper.selectList(new LambdaQueryWrapper<MappingLog>()
                .eq(MappingLog::getMappingId, id).orderByAsc(MappingLog::getId));
        List<Map<String, Object>> spans = new ArrayList<>();
        int step = 1;
        for (MappingLog l : logs) {
            String detail = switch (l.getAction() == null ? "" : l.getAction()) {
                case "CREATE" -> "创建（来源 " + nn(m.getSource()) + "，AI 来源默认落提议 PROPOSED）";
                case "UPDATE" -> "编辑留痕：改前 " + nn(l.getBeforeJson()) + " → 改后 " + nn(l.getAfterJson());
                case "TRANSITION" -> "流转 " + nn(l.getBeforeJson()) + " → " + nn(l.getAfterJson())
                        + "（流转需 EDITOR/ADMIN，运行时只认 ACTIVE）";
                default -> nn(l.getAction());
            };
            spans.add(span(step++, "生命周期·" + l.getAction(), detail, l.getOperator(), l.getCreatedAt()));
        }
        if (logs.isEmpty()) {
            spans.add(span(1, "历史映射", "V30 上线前的存量映射（无变更留痕），当前状态 " + nn(m.getStatus()),
                    m.getUpdatedBy(), m.getUpdatedAt()));
        }

        List<Map<String, Object>> anchors = new ArrayList<>();
        anchors.add(anchor("概念 " + m.getConceptCode(), "/ontology?concept=" + m.getConceptCode()));
        anchors.add(anchor("数据源绑定（生效/停用入口）", "/datasource"));

        List<String> actions = new ArrayList<>();
        if ("PROPOSED".equals(m.getStatus())) {
            actions.add("该映射仍在提议态，建模员确认后在数据源绑定页「生效」（AI 来源不直接进运行时）");
        } else if ("ACTIVE".equals(m.getStatus())) {
            actions.add("生效中：语义问数/实例投影/治理巡检都在消费；停用需 EDITOR/ADMIN 并留痕");
        } else {
            actions.add("已停用：可重新激活（DEPRECATED→ACTIVE），全部流转留痕可查");
        }

        return base("MAPPING", String.valueOf(m.getId()),
                "列 " + m.getDsCode() + "." + m.getTableName() + "." + m.getColumnName()
                        + " → 概念 " + m.getConceptCode() + (m.getAttrCode() == null ? "" : "(" + m.getAttrCode() + ")")
                        + "，当前 " + m.getStatus() + "，来源 " + nn(m.getSource()),
                spans, anchors, actions,
                List.of(anchor("数据源绑定", "/datasource")));
    }

    // ---------- CONCEPT：词表来源→创建→属性→生效映射→发布态（评审员的证据面） ----------

    private Map<String, Object> concept(String code) {
        Concept c = conceptMapper.selectOne(new LambdaQueryWrapper<Concept>()
                .eq(Concept::getCode, code).last("LIMIT 1"));
        if (c == null) {
            throw new BizException("概念不存在: " + code);
        }
        List<Attribute> attrs = attributeMapper.selectList(new LambdaQueryWrapper<Attribute>()
                .eq(Attribute::getConceptCode, code));
        List<Mapping> activeMappings = mappingMapper.selectList(new LambdaQueryWrapper<Mapping>()
                .eq(Mapping::getConceptCode, code).eq(Mapping::getStatus, "ACTIVE"));
        List<OntologyMiss> missSources = missMapper.selectList(new LambdaQueryWrapper<OntologyMiss>()
                .eq(OntologyMiss::getAdoptedConceptCode, code));

        List<Map<String, Object>> spans = new ArrayList<>();
        int step = 1;
        if (!missSources.isEmpty()) {
            OntologyMiss first = missSources.get(missSources.size() - 1);
            spans.add(span(step++, "词表缺口回流", missNarrative(first)
                    + (missSources.size() > 1 ? "（共 " + missSources.size() + " 条同类缺口）" : ""),
                    first.getSource(), first.getFirstSeen()));
        }
        spans.add(span(step++, "创建", "域 " + nn(c.getDomainCode()) + "，负责人 " + nn(c.getOwner())
                + "，当前版本 v" + c.getVersion(), c.getOwner(), c.getCreatedAt()));
        spans.add(span(step++, "属性层", attrs.isEmpty()
                ? "无属性定义（若概念已发布，覆盖门禁产 CONCEPT_NO_ATTR WARN——裸概念下游无字段可消费）"
                : attrs.size() + " 个属性（" + truncate(join(attrs.stream().map(a -> nn(a.getAttrCode())).toList()), 120) + "）",
                null, null));
        spans.add(span(step++, "生效映射", activeMappings.isEmpty()
                ? "无 ACTIVE 映射（有属性且概念已发布时，覆盖门禁产 ATTR_MAPPING_COVERAGE WARN——实例投影与问数将缺失）"
                : activeMappings.size() + " 条 ACTIVE（运行时只认生效映射）", null, null));
        spans.add(span(step++, "状态机", nn(c.getStatus())
                + "（DRAFT→REVIEW→PUBLISHED 需评审员/管理员；评审员不得发布本人负责的概念）",
                c.getOwner(), c.getUpdatedAt()));

        List<Map<String, Object>> anchors = new ArrayList<>();
        anchors.add(anchor("本体管理（概念详情）", "/ontology?concept=" + code));
        anchors.add(anchor("数据源绑定（该概念映射）", "/datasource"));
        if (!missSources.isEmpty()) {
            anchors.add(anchor("概念缺口（词表来源）", "/evolve"));
        }

        List<String> actions = new ArrayList<>();
        if (attrs.isEmpty()) {
            actions.add("补属性定义：裸概念发布时过不了覆盖门禁（CONCEPT_NO_ATTR）");
        }
        if (activeMappings.isEmpty()) {
            actions.add("补生效映射：无 ACTIVE 映射的概念无法被问数与实例投影消费");
        }
        if ("PUBLISHED".equals(c.getStatus()) && actions.isEmpty()) {
            actions.add("结构与映射齐备，可安全被下游消费");
        }

        return base("CONCEPT", code,
                "概念 " + code + "(" + nn(c.getName()) + ") 状态 " + nn(c.getStatus())
                        + "：" + attrs.size() + " 属性 / " + activeMappings.size() + " 生效映射"
                        + (missSources.isEmpty() ? "" : " / 词表来源 miss " + missSources.size() + " 条"),
                spans, anchors, actions,
                List.of(anchor("本体管理", "/ontology?concept=" + code),
                        anchor("链路追溯", "/link?concept=" + code)));
    }

    /** 词表缺口采纳叙述按事实分支：撤销/别名术语/概念采纳是三种不同的采纳结果，不能混说 */
    private static String missNarrative(OntologyMiss first) {
        String term = "说法「" + first.getTerm() + "」";
        if (first.getRevoked() != null && first.getRevoked() == 1) {
            return term + "曾由扩展提案池采纳（后已撤销，不再适用）";
        }
        if ("TERM".equals(first.getAdoptedAs())) {
            return term + "在扩展提案池被采纳为该概念的别名术语";
        }
        return term + "在扩展提案池被采纳为概念";
    }

    // ---------- 五段式公共骨架 ----------

    private Map<String, Object> base(String type, String key, String summary,
                                     List<Map<String, Object>> spans, List<Map<String, Object>> anchors,
                                     List<String> actions, List<Map<String, Object>> refs) {
        if (anchors.isEmpty()) {
            // 无锚点不输出：结构性保证至少一跳（被查对象自身的平台页）
            throw new IllegalStateException("证据链无锚点，拒绝输出");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("key", key);
        m.put("summary", summary);
        m.put("spans", spans);
        m.put("anchors", anchors);
        m.put("actions", actions);
        m.put("refs", refs);
        return m;
    }

    private Map<String, Object> span(int step, String title, String detail, String actor, java.time.LocalDateTime at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step);
        m.put("title", title);
        m.put("detail", detail);
        m.put("actor", actor);
        m.put("at", at == null ? null : at.toString().replace('T', ' '));
        return m;
    }

    private Map<String, Object> anchor(String label, String route) {
        return Map.of("label", label, "route", route);
    }

    private List<String> codes(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String join(List<String> parts) {
        return String.join(", ", parts);
    }

    private static String nn(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
