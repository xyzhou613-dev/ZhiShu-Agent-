package com.bemodel.ontology;

import com.bemodel.impact.ImpactService;
import com.bemodel.ontology.service.MetricService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指标监控 + 变更影响评估验证。
 * CANCEL_NOT_REFUND 实测应为 5（预埋故障未修复），阈值 0 → 必须告警。
 */
@SpringBootTest
class MetricMonitorTest {

    @Autowired
    private MetricService metricService;
    @Autowired
    private ImpactService impactService;

    @Test
    void cancelNotRefundShouldAlarm() {
        Map<String, Object> r = metricService.evaluate("CANCEL_NOT_REFUND");
        assertEquals(5, ((Number) r.get("value")).intValue(), "预埋故障应为5笔取消未退费");
        assertEquals(Boolean.TRUE, r.get("alarm"), "超过阈值0必须告警");
    }

    @Test
    void evaluateAllShouldCoverMonitoredMetrics() {
        List<Map<String, Object>> all = metricService.evaluateAll();
        // 种子 4 条 + 真实库试点新增(mo_lis 危急值报告数/检验申请总数)——按编码断言,不锁死总数:
        // 真实数据源接入后指标会增长,厂商库的真实告警(如危急值)也是合法告警
        List<String> codes = all.stream().map(m -> String.valueOf(m.get("metricCode"))).toList();
        assertTrue(codes.containsAll(List.of("DISCHARGE_COUNT", "LAB_CANCEL_RATE", "AVG_INP_FEE", "CANCEL_NOT_REFUND")),
                "种子指标应全部被巡检覆盖");
        long alarms = all.stream().filter(m -> Boolean.TRUE.equals(m.get("alarm"))).count();
        assertTrue(alarms >= 1, "已绑定探针的指标应全部被巡检(种子4条+试点新增)");
        assertTrue(all.stream().filter(m -> Boolean.TRUE.equals(m.get("alarm")))
                        .anyMatch(m -> "CANCEL_NOT_REFUND".equals(m.get("metricCode"))),
                "取消未退费必须告警");
    }

    @Test
    void impactAnalysisShouldCoverAffectedAssets() {
        Map<String, Object> r = impactService.analyze("LAB_APPLY", "检验申请状态字典新增状态码");
        Map<String, ?> tables = (Map<String, ?>) r.get("affectedTables");
        assertTrue(tables.containsKey("DS_LIS"), "应识别LIS库映射受影响");
        assertFalse(((List<?>) r.get("relatedConcepts")).isEmpty(), "应列出上下游概念");
        assertTrue(String.valueOf(r.get("advice")).length() > 20, "应生成评估意见");
    }
}
