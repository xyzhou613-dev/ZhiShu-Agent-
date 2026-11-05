package com.bemodel.link.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.common.Masking;
import com.bemodel.common.PageResult;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.link.entity.LinkNode;
import com.bemodel.link.entity.LinkRel;
import com.bemodel.link.mapper.LinkNodeMapper;
import com.bemodel.link.mapper.LinkRelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LinkService extends ServiceImpl<LinkNodeMapper, LinkNode> {

    private final DatasourceService datasourceService;
    private final LinkRelMapper relMapper;

    public List<LinkNode> list(String nodeType, String conceptCode) {
        return lambdaQuery()
                .eq(nodeType != null && !nodeType.isBlank(), LinkNode::getNodeType, nodeType)
                .eq(conceptCode != null && !conceptCode.isBlank(), LinkNode::getConceptCode, conceptCode)
                .orderByDesc(LinkNode::getOccurredAt)
                .list();
    }

    /** 服务端分页列表（链路节点会持续累积，必须分页） */
    public PageResult<LinkNode> listPage(String nodeType, String conceptCode, int pageNum, int pageSize) {
        long total = lambdaQuery()
                .eq(nodeType != null && !nodeType.isBlank(), LinkNode::getNodeType, nodeType)
                .eq(conceptCode != null && !conceptCode.isBlank(), LinkNode::getConceptCode, conceptCode)
                .count();
        List<LinkNode> list = lambdaQuery()
                .eq(nodeType != null && !nodeType.isBlank(), LinkNode::getNodeType, nodeType)
                .eq(conceptCode != null && !conceptCode.isBlank(), LinkNode::getConceptCode, conceptCode)
                .orderByDesc(LinkNode::getOccurredAt)
                .last("LIMIT " + pageSize + " OFFSET " + (pageNum - 1) * pageSize)
                .list();
        return PageResult.of(list, total, pageNum, pageSize);
    }

    public LinkNode getByRefNo(String refNo) {
        return lambdaQuery().eq(LinkNode::getRefNo, refNo).one();
    }

    /** 以概念为中枢的全链路视图：按节点类型分组，组内按时间排序 */
    public Map<String, List<LinkNode>> chainByConcept(String conceptCode) {
        List<LinkNode> nodes = list(null, conceptCode);
        return nodes.stream().collect(Collectors.groupingBy(
                LinkNode::getNodeType, TreeMap::new, Collectors.toList()));
    }

    // ---------- 节点级追溯边（link_rel） ----------

    /** 新增追溯边：两端节点必须已存在，幂等（重复边静默忽略） */
    public LinkRel addRel(String fromRefNo, String toRefNo, String relType, String remark) {
        if (fromRefNo == null || fromRefNo.isBlank() || toRefNo == null || toRefNo.isBlank()) {
            throw new BizException("边的两端单号不能为空");
        }
        if (fromRefNo.equals(toRefNo)) {
            throw new BizException("不允许节点关联自身: " + fromRefNo);
        }
        if (getByRefNo(fromRefNo) == null) {
            throw new BizException("上游节点不存在: " + fromRefNo);
        }
        if (getByRefNo(toRefNo) == null) {
            throw new BizException("下游节点不存在: " + toRefNo);
        }
        LinkRel rel = new LinkRel();
        rel.setFromRefNo(fromRefNo);
        rel.setToRefNo(toRefNo);
        rel.setRelType(relType == null || relType.isBlank() ? "RELATES" : relType);
        rel.setRemark(remark);
        try {
            relMapper.insert(rel);
        } catch (DuplicateKeyException e) {
            return relMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LinkRel>()
                    .eq(LinkRel::getFromRefNo, fromRefNo)
                    .eq(LinkRel::getToRefNo, toRefNo)
                    .eq(LinkRel::getRelType, rel.getRelType()));
        }
        return rel;
    }

    public void removeRel(Long id) {
        relMapper.deleteById(id);
    }

    /** 节点直接相连的边 */
    public List<LinkRel> relsOf(String refNo) {
        return relMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LinkRel>()
                .eq(LinkRel::getFromRefNo, refNo)
                .or()
                .eq(LinkRel::getToRefNo, refNo));
    }

    /** 概念节点集合触碰到的全部追溯边（供链路视图的结论推导使用） */
    public List<LinkRel> relsByConcept(String conceptCode) {
        Set<String> refs = list(null, conceptCode).stream()
                .map(LinkNode::getRefNo).collect(Collectors.toSet());
        if (refs.isEmpty()) {
            return List.of();
        }
        return relMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LinkRel>()
                .in(LinkRel::getFromRefNo, refs)
                .or()
                .in(LinkRel::getToRefNo, refs));
    }

    /**
     * 从任一节点出发的追溯子图：沿边双向BFS（上游=根因方向，下游=影响/闭环方向）。
     * 返回 { root, nodes, edges }，edges 携带方向，由前端区分上下游展示。
     */
    public Map<String, Object> trace(String refNo) {
        LinkNode root = getByRefNo(refNo);
        if (root == null) {
            throw new BizException("链路节点不存在: " + refNo);
        }
        int maxDepth = 6;
        Set<String> visited = new HashSet<>();
        Set<Long> seenEdges = new HashSet<>();
        List<LinkRel> edges = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        visited.add(refNo);
        queue.add(refNo);
        int depth = 0;
        while (!queue.isEmpty() && depth < maxDepth) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String cur = queue.poll();
                for (LinkRel rel : relsOf(cur)) {
                    if (seenEdges.add(rel.getId())) {
                        edges.add(rel);
                    }
                    String next = rel.getFromRefNo().equals(cur) ? rel.getToRefNo() : rel.getFromRefNo();
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            depth++;
        }
        Map<String, LinkNode> nodeMap = new LinkedHashMap<>();
        nodeMap.put(refNo, root);
        if (visited.size() > 1) {
            List<String> others = visited.stream().filter(r -> !r.equals(refNo)).toList();
            for (LinkNode n : lambdaQuery().in(LinkNode::getRefNo, others).list()) {
                nodeMap.put(n.getRefNo(), n);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", refNo);
        result.put("nodes", nodeMap.values());
        result.put("edges", edges);
        return result;
    }

    /**
     * 异常自动转客服工单：扫描「医嘱已取消但费用未退」的患者，
     * 对尚无工单的患者自动生成 TICKET（幂等：已有工单的患者跳过）。
     * 即「指标告警 → 客服介入 → 根因分析」自动化闭环的第一环。
     */
    public Map<String, Object> autoTicket() {
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        List<Map<String, Object>> broken = his.queryForList(
                "SELECT f.inhos_no, i.patient_name, COUNT(*) AS cnt, SUM(f.amount) AS amt, MIN(f.charge_time) AS first_at " +
                        "FROM fee_detail f JOIN medical_order o ON f.order_id = o.order_id " +
                        "JOIN inpatient i ON i.inhos_no = f.inhos_no " +
                        "WHERE o.order_status = '2' AND f.fee_status = '1' " +
                        "GROUP BY f.inhos_no, i.patient_name");

        List<LinkNode> existingTickets = lambdaQuery().eq(LinkNode::getNodeType, "TICKET").list();
        List<Map<String, Object>> created = new ArrayList<>();
        int seq = existingTickets.size();
        for (Map<String, Object> b : broken) {
            String inhosNo = String.valueOf(b.get("inhos_no"));
            boolean hasTicket = existingTickets.stream()
                    .anyMatch(t -> t.getPayload() != null && t.getPayload().contains(inhosNo));
            if (hasTicket) {
                continue;
            }
            LinkNode ticket = new LinkNode();
            ticket.setNodeType("TICKET");
            ticket.setRefNo("T-AUTO-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + "-" + String.format("%03d", ++seq));
            ticket.setTitle("系统预警：患者" + Masking.maskName(String.valueOf(b.get("patient_name")))
                    + "存在" + b.get("cnt") + "笔取消未退费费用");
            ticket.setConceptCode("FEE_DETAIL");
            ticket.setStatus("待处理");
            ticket.setOccurredAt(LocalDateTime.now());
            ticket.setPayload(String.format(
                    "{\"patient\":\"%s\",\"inhos_no\":\"%s\",\"fee_count\":%s,\"amount\":%s,\"source\":\"指标监控自动检测（取消未退费笔数告警）\"}",
                    Masking.maskName(String.valueOf(b.get("patient_name"))), inhosNo, b.get("cnt"), b.get("amt")));
            save(ticket);
            created.add(Map.of("refNo", ticket.getRefNo(), "title", ticket.getTitle()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scannedPatients", broken.size());
        result.put("createdCount", created.size());
        result.put("created", created);
        return result;
    }
}
