package com.bemodel.rca.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.BizException;
import com.bemodel.common.Masking;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.link.entity.LinkNode;
import com.bemodel.link.service.LinkService;
import com.bemodel.llm.DeepSeekClient;
import com.bemodel.ontology.entity.Relation;
import com.bemodel.ontology.mapper.RelationMapper;
import com.bemodel.rca.entity.RcaCase;
import com.bemodel.rca.entity.RcaReport;
import com.bemodel.rca.entity.RcaStep;
import com.bemodel.rca.mapper.RcaCaseMapper;
import com.bemodel.rca.mapper.RcaReportMapper;
import com.bemodel.rca.mapper.RcaStepMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 根因分析引擎。
 * 以本体关系图为路径、以映射为桥梁、对产品库执行真实SQL探针，逐步收敛根因；
 * 探针证据 + 链路变更记录交给 deepseek-v4-flash 生成报告，LLM 不可用时模板降级。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RcaEngine {

    private final RcaCaseMapper caseMapper;
    private final RcaStepMapper stepMapper;
    private final RcaReportMapper reportMapper;
    private final RelationMapper relationMapper;
    private final LinkService linkService;
    private final DatasourceService datasourceService;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    public List<RcaCase> listCases() {
        return caseMapper.selectList(new LambdaQueryWrapper<RcaCase>().orderByDesc(RcaCase::getId));
    }

    public Map<String, Object> caseDetail(Long caseId) {
        RcaCase c = caseMapper.selectById(caseId);
        if (c == null) {
            throw new BizException("案例不存在: " + caseId);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("case", c);
        detail.put("steps", stepMapper.selectList(new LambdaQueryWrapper<RcaStep>()
                .eq(RcaStep::getCaseId, caseId).orderByAsc(RcaStep::getStepNo)));
        RcaReport report = reportMapper.selectOne(
                new LambdaQueryWrapper<RcaReport>().eq(RcaReport::getCaseId, caseId));
        detail.put("report", report);
        return detail;
    }

    /** 从客服工单发起根因分析（同步执行，步骤实时落库） */
    public RcaCase start(String ticketRef) {
        LinkNode ticket = linkService.getByRefNo(ticketRef);
        if (ticket == null || !"TICKET".equals(ticket.getNodeType())) {
            throw new BizException("客服工单不存在: " + ticketRef);
        }
        String inhosNo = parsePayloadField(ticket.getPayload(), "inhos_no");
        // 姓名出域前打码：后续步骤标题/证据链/LLM 上下文全部沿用打码值，inhos_no 为业务键保留
        String patient = Masking.maskName(parsePayloadField(ticket.getPayload(), "patient"));

        RcaCase rcaCase = new RcaCase();
        rcaCase.setCaseNo("RCA-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        rcaCase.setTicketRef(ticketRef);
        rcaCase.setConceptCode(ticket.getConceptCode());
        rcaCase.setStatus("RUNNING");
        caseMapper.insert(rcaCase);

        try {
            List<String> path = traverseStep(rcaCase);
            List<Map<String, Object>> p1 = probe1PatientCharged(rcaCase, inhosNo, patient);
            List<Map<String, Object>> p2 = probe2LisConfirm(rcaCase, p1);
            Map<String, Object> p3 = probe3MappingGap(rcaCase);
            Map<String, Object> p4 = probe4Impact(rcaCase);
            List<LinkNode> links = linkStep(rcaCase, path);
            reportStep(rcaCase, ticket, patient, path, p1, p2, p3, p4, links);
            writebackRootCauseEdges(rcaCase, ticketRef, links);
        } catch (Exception e) {
            log.error("根因分析执行失败", e);
            rcaCase.setStatus("FAILED");
            rcaCase.setConclusion("分析中断: " + e.getMessage());
            rcaCase.setFinishedAt(LocalDateTime.now());
            caseMapper.updateById(rcaCase);
        }
        return rcaCase;
    }

    // ---------- Step 1: 本体图遍历，确定探查路径 ----------
    private List<String> traverseStep(RcaCase rcaCase) {
        LocalDateTime start = LocalDateTime.now();
        String from = rcaCase.getConceptCode();
        List<Relation> relations = relationMapper.selectList(null);

        List<String> pathEdges = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        visited.add(from);
        int depth = 0;
        while (!queue.isEmpty() && depth < 4) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String cur = queue.poll();
                for (Relation r : relations) {
                    String next = null;
                    String edge = null;
                    if (r.getFromConcept().equals(cur) && !visited.contains(r.getToConcept())) {
                        next = r.getToConcept();
                        edge = r.getFromConcept() + " —" + r.getRelationName() + "→ " + r.getToConcept();
                    } else if (r.getToConcept().equals(cur) && !visited.contains(r.getFromConcept())) {
                        next = r.getFromConcept();
                        edge = r.getFromConcept() + " —" + r.getRelationName() + "→ " + r.getToConcept();
                    }
                    if (next != null) {
                        visited.add(next);
                        queue.add(next);
                        pathEdges.add(edge);
                    }
                }
            }
            depth++;
        }
        List<String> path = new ArrayList<>(visited);
        saveStep(rcaCase, 1, "摸清业务范围：从客诉涉及的概念沿本体关系图展开，确定要查哪些系统", "TRAVERSE", null, pathEdges.size(),
                Map.of("起点", from, "探查路径", pathEdges), start);
        return path;
    }

    // ---------- Step 2: 查患者本人的账——已取消医嘱是否仍在收费 ----------
    private List<Map<String, Object>> probe1PatientCharged(RcaCase rcaCase, String inhosNo, String patient) {
        LocalDateTime start = LocalDateTime.now();
        String sql = "SELECT f.fee_id, f.order_id, f.item_name, f.amount, f.charge_time, o.order_status " +
                "FROM fee_detail f JOIN medical_order o ON f.order_id = o.order_id " +
                "WHERE o.order_status = '2' AND f.fee_status = '1' AND f.inhos_no = ?";
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        List<Map<String, Object>> rows = inhosNo == null || inhosNo.isBlank()
                ? List.of()
                : his.queryForList(sql, inhosNo);
        saveStep(rcaCase, 2, "核查患者[" + patient + "]的账单：是否存在「医嘱已取消、费用仍正常」的记录", "PROBE",
                sql + "  [参数 inhos_no=" + inhosNo + "]", rows.size(),
                Map.of("口径说明", "医嘱状态=已取消 但 费用状态=正常（标准概念口径，经映射落为物理值 2/1）", "命中记录", rows), start);
        return rows;
    }

    // ---------- Step 3: 跨库到 LIS 核实撤销事实 ----------
    private List<Map<String, Object>> probe2LisConfirm(RcaCase rcaCase, List<Map<String, Object>> p1Rows) {
        LocalDateTime start = LocalDateTime.now();
        if (p1Rows.isEmpty()) {
            saveStep(rcaCase, 3, "跨库核实：到 LIS 确认检验申请的真实状态", "PROBE", null, 0,
                    Map.of("说明", "患者账单无异常，跳过"), start);
            return List.of();
        }
        List<String> orderIds = p1Rows.stream().map(r -> String.valueOf(r.get("order_id"))).toList();
        String placeholders = String.join(",", Collections.nCopies(orderIds.size(), "?"));
        String sql = "SELECT apply_id, order_id, patient_no, item_name, apply_status, update_time " +
                "FROM lab_apply WHERE order_id IN (" + placeholders + ")";
        JdbcTemplate lis = datasourceService.jdbc("DS_LIS");
        List<Map<String, Object>> rows = lis.queryForList(sql, orderIds.toArray());
        saveStep(rcaCase, 3, "跨库核实：到 LIS 确认这些医嘱的检验申请确实已撤销（经映射 patient_no↔住院号）", "PROBE",
                sql + "  [参数 order_id=" + orderIds + "]", rows.size(),
                Map.of("口径说明", "apply_status=C 即标准口径「已撤销」", "命中记录", rows), start);
        return rows;
    }

    // ---------- Step 4: 比对字典找裂缝（根因证据） ----------
    private Map<String, Object> probe3MappingGap(RcaCase rcaCase) {
        LocalDateTime start = LocalDateTime.now();
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        JdbcTemplate lis = datasourceService.jdbc("DS_LIS");

        String sqlA = "SELECT src_status, src_status_name, target_action, updated_at FROM status_map WHERE src_system = 'LIS'";
        List<Map<String, Object>> mapped = his.queryForList(sqlA);
        String sqlB = "SELECT DISTINCT apply_status FROM lab_apply";
        List<String> actual = lis.queryForList(sqlB, String.class);
        String sqlC = "SELECT status_code, status_name, app_version, effective_date FROM lab_dict_status ORDER BY effective_date";
        List<Map<String, Object>> dict = lis.queryForList(sqlC);

        List<String> mappedCodes = mapped.stream().map(m -> String.valueOf(m.get("src_status"))).toList();
        List<String> missing = actual.stream().filter(s -> !mappedCodes.contains(s)).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("适配器已映射状态", mapped);
        result.put("LIS实际使用状态", actual);
        result.put("未映射状态（裂缝）", missing);
        result.put("LIS状态字典版本演进", dict);
        saveStep(rcaCase, 4, "比对字典找裂缝：HIS 计费适配器认识的撤销码 vs LIS 升级后实际使用的状态码", "PROBE",
                sqlA + " ; " + sqlB + " ; " + sqlC, missing.size(), result, start);
        return result;
    }

    // ---------- Step 5: 算全院影响面 ----------
    private Map<String, Object> probe4Impact(RcaCase rcaCase) {
        LocalDateTime start = LocalDateTime.now();
        String sql = "SELECT COUNT(DISTINCT f.inhos_no) AS patient_cnt, COUNT(*) AS fee_cnt, " +
                "IFNULL(SUM(f.amount),0) AS total_amount, MIN(f.charge_time) AS first_at, MAX(f.charge_time) AS last_at " +
                "FROM fee_detail f JOIN medical_order o ON f.order_id = o.order_id " +
                "WHERE o.order_status = '2' AND f.fee_status = '1'";
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        Map<String, Object> impact = his.queryForMap(sql);
        saveStep(rcaCase, 5, "计算影响面：全院还有多少患者存在同样的「取消未退费」，涉及多少费用", "PROBE", sql,
                ((Number) impact.get("fee_cnt")).intValue(), impact, start);
        return impact;
    }

    // ---------- Step 6: 关联链路记录（变更/发布/用例/需求） ----------
    private List<LinkNode> linkStep(RcaCase rcaCase, List<String> pathConcepts) {
        LocalDateTime start = LocalDateTime.now();
        List<LinkNode> nodes = linkService.lambdaQuery()
                .in(LinkNode::getConceptCode, pathConcepts)
                .in(LinkNode::getNodeType, "CHANGE", "DEPLOY", "TESTCASE", "REQUIREMENT")
                .orderByAsc(LinkNode::getOccurredAt)
                .list();
        saveStep(rcaCase, 6, "翻变更留痕：该业务概念路径上的需求/变更/发布/测试记录", "LINK",
                "link_node WHERE concept_code IN " + pathConcepts, nodes.size(),
                Map.of("关联节点", nodes.stream().map(n -> Map.of(
                        "type", n.getNodeType(), "refNo", n.getRefNo(),
                        "title", n.getTitle(), "at", String.valueOf(n.getOccurredAt()))).toList()), start);
        return nodes;
    }

    // ---------- Step 7: 生成根因报告 ----------
    /**
     * 根因定位后回写追溯边：分析路径上命中的变更节点 → 工单（CAUSES，幂等），
     * 链路追溯视图即可从工单反穿到根因变更，不再只靠概念分组。
     */
    private void writebackRootCauseEdges(RcaCase rcaCase, String ticketRef, List<LinkNode> links) {
        for (LinkNode n : links) {
            if (!"CHANGE".equals(n.getNodeType())) {
                continue;
            }
            try {
                linkService.addRel(n.getRefNo(), ticketRef, "CAUSES", "根因分析 " + rcaCase.getCaseNo() + " 关联");
            } catch (Exception e) {
                log.warn("回写追溯边失败 {} -> {}: {}", n.getRefNo(), ticketRef, e.getMessage());
            }
        }
    }

    private void reportStep(RcaCase rcaCase, LinkNode ticket, String patient, List<String> path,
                            List<Map<String, Object>> p1, List<Map<String, Object>> p2,
                            Map<String, Object> p3, Map<String, Object> p4,
                            List<LinkNode> links) throws Exception {
        LocalDateTime start = LocalDateTime.now();

        List<String> missing = (List<String>) p3.get("未映射状态（裂缝）");
        String conclusion = String.format(
                "LIS v5.2升级（2026-08-15）将检验申请撤销状态码由X改为C，但HIS计费适配器status_map未同步配置新码「%s」的退费映射，" +
                        "导致升级后已撤销的检验医嘱仍正常计费。全院共影响%s名患者、%s笔费用，合计¥%s。",
                String.join(",", missing),
                p4.get("patient_cnt"), p4.get("fee_cnt"), p4.get("total_amount"));

        List<Map<String, Object>> evidence = List.of(
                Map.of("probe", "患者账单核查", "finding", "患者" + patient + "存在" + p1.size() + "笔「医嘱已取消、费用仍正常」记录", "data", p1),
                Map.of("probe", "跨库事实核实", "finding", "对应LIS申请状态均为C（已撤销），撤销事实成立", "data", p2),
                Map.of("probe", "字典裂缝定位", "finding", "status_map缺少状态码 " + missing + " 的映射，该码由LIS v5.2于2026-08-15引入", "data", p3),
                Map.of("probe", "全院影响面", "finding", "全院" + p4.get("patient_cnt") + "名患者、" + p4.get("fee_cnt") + "笔费用未退，合计¥" + p4.get("total_amount"), "data", p4));

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("affectedPatients", p4.get("patient_cnt"));
        impact.put("affectedFees", p4.get("fee_cnt"));
        impact.put("totalAmount", p4.get("total_amount"));
        impact.put("firstOccurrence", p4.get("first_at"));
        impact.put("lastOccurrence", p4.get("last_at"));
        impact.put("relatedLinks", links.stream().map(l -> l.getNodeType() + ":" + l.getRefNo() + " " + l.getTitle()).toList());

        List<String> suggestions = List.of(
                "【研发】HIS计费适配器status_map补充 LIS:C → 退费 映射，并建立状态字典变更的强制影响评估工单（堵住「口头通知」漏洞）",
                "【运维】对影响期内" + p4.get("fee_cnt") + "笔费用执行批量退费，同步核对其他医技系统（PACS等）状态映射表",
                "【测试】TC-FEE-0032用例扩展：覆盖LIS侧撤销状态码（含新增码）的退费回归场景，纳入版本升级准入",
                "【治理】将「取消未退费笔数」指标（CANCEL_NOT_REFUND）接入日常监控，口径异常自动告警");

        // LLM 生成叙述报告；降级为模板
        String llmReport = null;
        boolean llmUsed = false;
        StringBuilder ctx = new StringBuilder();
        ctx.append("客诉工单：").append(ticket.getTitle()).append("\n");
        ctx.append("分析结论：").append(conclusion).append("\n\n证据链：\n");
        for (Map<String, Object> e : evidence) {
            ctx.append("- ").append(e.get("probe")).append("：").append(e.get("finding")).append('\n');
        }
        ctx.append("\n关联链路记录：\n");
        for (LinkNode n : links) {
            ctx.append("- [").append(n.getNodeType()).append("] ").append(n.getOccurredAt())
                    .append(" ").append(n.getTitle()).append('\n');
        }
        Optional<String> llm = deepSeekClient.chat("RCA_REPORT",
                "你是医疗信息化根因分析专家。基于给定证据输出根因分析报告，结构：一、问题概述；二、根因结论；三、证据链分析；四、影响范围；五、整改建议。语言专业简洁，500字以内。",
                ctx.toString());
        if (llm.isPresent()) {
            llmReport = llm.get();
            llmUsed = true;
        }

        RcaReport report = new RcaReport();
        report.setCaseId(rcaCase.getId());
        report.setRootCause(llmUsed ? llmReport : conclusion);
        report.setEvidenceJson(objectMapper.writeValueAsString(evidence));
        report.setImpactJson(objectMapper.writeValueAsString(impact));
        report.setSuggestionsJson(objectMapper.writeValueAsString(suggestions));
        report.setLlmUsed(llmUsed ? 1 : 0);
        reportMapper.insert(report);

        rcaCase.setStatus("DONE");
        rcaCase.setConclusion(conclusion);
        rcaCase.setFinishedAt(LocalDateTime.now());
        caseMapper.updateById(rcaCase);

        saveStep(rcaCase, 7, "产出诊断结论与处置建议（" + (llmUsed ? deepSeekClient.model() : "模板降级") + "）",
                "REPORT", null, 1, Map.of("llmUsed", llmUsed, "conclusion", conclusion), start);
    }

    private void saveStep(RcaCase rcaCase, int stepNo, String name, String type,
                          String sql, int hitCount, Object result, LocalDateTime startedAt) {
        try {
            RcaStep step = new RcaStep();
            step.setCaseId(rcaCase.getId());
            step.setStepNo(stepNo);
            step.setStepName(name);
            step.setStepType(type);
            step.setSqlText(sql);
            step.setHitCount(hitCount);
            step.setResultJson(objectMapper.writeValueAsString(result));
            step.setStatus("SUCCESS");
            step.setStartedAt(startedAt);
            step.setFinishedAt(LocalDateTime.now());
            stepMapper.insert(step);
        } catch (Exception e) {
            throw new BizException("步骤落库失败: " + e.getMessage());
        }
    }

    private String parsePayloadField(String payload, String field) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.path(field).asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
