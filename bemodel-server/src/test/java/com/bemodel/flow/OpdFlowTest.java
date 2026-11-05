package com.bemodel.flow;

import com.bemodel.link.service.LinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 门诊闭环 + 异常自动转工单验证。
 */
@SpringBootTest
class OpdFlowTest {

    @Autowired
    private FlowService flowService;
    @Autowired
    private LinkService linkService;

    @Test
    void opdPatientsShouldBeSeeded() {
        var page = flowService.opdPatients(null, 1, 20);
        assertEquals(25, page.getTotal(), "应有25名门诊患者");
        assertEquals(20, page.getList().size(), "第1页20条（服务端分页）");
        assertEquals(5, flowService.opdPatients(null, 2, 20).getList().size(), "第2页5条");
        assertTrue(flowService.opdPatients(null, 1, 30).getList().stream()
                        .anyMatch(p -> "已退号".equals(p.get("regStatusName"))),
                "应含退号场景（值字典翻译生效）");
    }

    @Test
    void opdLoopShouldAssembleAcrossSystems() {
        // 找一名有检验处方的门诊患者（i%3==0 的第一名：KC2026900001）
        Map<String, Object> loop = flowService.opdLoop("KC2026900001");
        assertNotNull(loop.get("register"));
        assertNotNull(loop.get("visit"), "已挂号患者应有看诊记录");

        List<Map<String, Object>> prescs = (List<Map<String, Object>>) loop.get("prescriptions");
        assertFalse(prescs.isEmpty());
        // 检验处方有LIS环节、药品处方有药房环节
        assertTrue(prescs.stream().filter(p -> "检验".equals(p.get("itemType")) && "CLOSED".equals(p.get("loopStatus")))
                .allMatch(p -> p.get("labReport") != null), "已执行检验处方必须有LIS报告");
        assertTrue(prescs.stream().filter(p -> "药品".equals(p.get("itemType")) && "CLOSED".equals(p.get("loopStatus")))
                .allMatch(p -> p.get("dispense") != null), "已执行药品处方必须有药房发药");
        // 处方状态经平台值字典翻译为标准医嘱口径
        assertTrue(prescs.stream().allMatch(p ->
                List.of("已执行", "未执行", "已取消").contains(p.get("statusName"))));

        List<Map<String, Object>> timeline = (List<Map<String, Object>>) loop.get("timeline");
        assertTrue(timeline.stream().anyMatch(e -> "门诊".equals(e.get("system"))));
        assertFalse(((List<?>) loop.get("payments")).isEmpty(), "应有门诊缴费记录");
    }

    @Test
    void autoTicketShouldCreateOnlyForUnticketedPatients() {
        // 先清理历史自动工单，保证用例幂等
        linkService.remove(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.bemodel.link.entity.LinkNode>()
                .likeRight(com.bemodel.link.entity.LinkNode::getRefNo, "T-AUTO-"));

        Map<String, Object> first = linkService.autoTicket();
        // 预埋故障影响5名患者：张建国、王秀兰已有工单 → 新生成3张（王强/刘伟/杨光）
        assertEquals(5, ((Number) first.get("scannedPatients")).intValue());
        assertEquals(3, ((Number) first.get("createdCount")).intValue(), "应为无工单的3名患者各建一张");

        Map<String, Object> second = linkService.autoTicket();
        assertEquals(0, ((Number) second.get("createdCount")).intValue(), "幂等：二次运行不再生成");
    }
}
