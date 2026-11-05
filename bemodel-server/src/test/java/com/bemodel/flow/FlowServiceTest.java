package com.bemodel.flow;

import com.bemodel.common.PageResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 医嘱全闭环装配验证：张建国（客诉患者）的闭环中
 * 那条「撤销仍收费」的血常规医嘱必须呈现为异常中断（BROKEN），
 * 药品医嘱必须有药房发药环节，检验医嘱必须有报告环节。
 */
@SpringBootTest
class FlowServiceTest {

    @Autowired
    private FlowService flowService;

    @Test
    void patientsShouldBeFortyOne() {
        PageResult<Map<String, Object>> page = flowService.patients(null, 1, 20);
        assertEquals(41, page.getTotal(), "应有41名虚构患者（V13新增儿童患者韩小梅）");
        assertEquals(20, page.getList().size(), "第1页20条（服务端分页）");
        PageResult<Map<String, Object>> page2 = flowService.patients(null, 2, 20);
        assertEquals(20, page2.getList().size(), "第2页20条");
        PageResult<Map<String, Object>> page3 = flowService.patients(null, 3, 20);
        assertEquals(1, page3.getList().size(), "第3页1条");
        assertTrue(page.getList().stream().allMatch(p -> ((Number) p.get("orderCount")).intValue() > 0),
                "每名患者都应有医嘱（数据完整）");
    }

    @Test
    void loopShouldAssembleFullChainAcrossFourSystems() {
        Map<String, Object> loop = flowService.loop("ZY20260815001");

        List<Map<String, Object>> orders = (List<Map<String, Object>>) loop.get("orders");
        assertFalse(orders.isEmpty());

        // 恰好 1 条异常中断（故障血常规），其余全部正常闭环
        long broken = orders.stream().filter(o -> "BROKEN".equals(o.get("loopStatus"))).count();
        assertEquals(1, broken, "张建国应恰好1条异常中断医嘱");

        // 药品医嘱有发药环节，检验医嘱有报告环节，检查医嘱有PACS报告环节
        assertTrue(orders.stream().filter(o -> "药品".equals(o.get("orderType")))
                .allMatch(o -> o.get("dispense") != null), "药品医嘱必须有发药记录");
        assertTrue(orders.stream()
                .filter(o -> "检验".equals(o.get("orderType")) && "1".equals(String.valueOf(o.get("orderStatus"))))
                .allMatch(o -> o.get("labReport") != null), "已执行检验医嘱必须有报告");
        assertTrue(orders.stream()
                .filter(o -> "检查".equals(o.get("orderType")) && "1".equals(String.valueOf(o.get("orderStatus"))))
                .allMatch(o -> o.get("examReport") != null), "已执行检查医嘱必须有PACS报告");
        // 护士站执行确认：已执行医嘱必须有执行确认环节
        assertTrue(orders.stream()
                .filter(o -> "1".equals(String.valueOf(o.get("orderStatus"))))
                .allMatch(o -> o.get("nurseExec") != null), "已执行医嘱必须有护士执行确认");

        // 缴费、结算、时间线齐备
        assertFalse(((List<?>) loop.get("payments")).isEmpty(), "应有缴费记录");
        assertNotNull(loop.get("settlement"), "出院患者应有结算");
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) loop.get("timeline");
        assertTrue(timeline.stream().anyMatch(e -> "护士站".equals(e.get("system"))), "时间线应含护士站环节");
        assertTrue(timeline.stream().anyMatch(e -> "PACS".equals(e.get("system"))), "时间线应含PACS环节");
        assertTrue(timeline.stream().anyMatch(e -> "药房".equals(e.get("system"))), "时间线应含药房环节");
        assertTrue(timeline.stream().anyMatch(e -> "LIS".equals(e.get("system"))), "时间线应含LIS环节");
        assertTrue(timeline.stream().anyMatch(e -> "收费".equals(e.get("system"))), "时间线应含收费环节");

        // 状态码翻译走平台值字典（取消医嘱应显示标准口径）
        assertTrue(orders.stream().filter(o -> "BROKEN".equals(o.get("loopStatus")))
                .allMatch(o -> "已取消".equals(o.get("orderStatusName"))));

        // 处方审核环节（医嘱闭环必经）：药品医嘱必须有药师审核记录，时间线含审核事件
        assertTrue(orders.stream().filter(o -> "药品".equals(o.get("orderType"))
                        && "1".equals(String.valueOf(o.get("orderStatus"))))
                .allMatch(o -> o.get("prescReview") != null), "已执行药品医嘱必须有处方审核环节");
        assertTrue(timeline.stream().anyMatch(e -> "处方审核通过".equals(e.get("event"))), "时间线应含处方审核环节");
    }

    @Test
    void overdoseOrderShouldBeRejectedByPharmacist() {
        // 冯雪超量布洛芬医嘱（1.0g tid）必须被药师驳回——与 SHACL Shape 2 同一违规的平台内证据
        Map<String, Object> loop = flowService.loop("ZY20260728012");
        List<Map<String, Object>> orders = (List<Map<String, Object>>) loop.get("orders");
        Map<String, Object> overdose = orders.stream()
                .filter(o -> "D002".equals(String.valueOf(o.get("itemCode"))))
                .findFirst().orElseThrow(() -> new AssertionError("冯雪应有 D002 布洛芬医嘱"));
        Map<String, Object> review = (Map<String, Object>) overdose.get("prescReview");
        assertNotNull(review, "超量医嘱必须有审核记录");
        assertEquals("驳回", String.valueOf(review.get("review_result")), "超量医嘱必须被药师驳回");
        assertTrue(String.valueOf(review.get("reject_reason")).contains("2.4g"), "驳回原因应含日最大剂量");
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) loop.get("timeline");
        assertTrue(timeline.stream().anyMatch(e -> "处方审核驳回".equals(e.get("event"))),
                "时间线应含审核驳回事件");
    }
}
