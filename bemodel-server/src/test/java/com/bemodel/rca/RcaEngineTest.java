package com.bemodel.rca;

import com.bemodel.rca.entity.RcaCase;
import com.bemodel.rca.service.RcaEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 根因分析案例端到端验证：客诉工单 → 探针命中预埋故障 → 报告生成。
 * 依赖本地 MySQL（与开发库一致，种子数据幂等）。
 */
@SpringBootTest
class RcaEngineTest {

    @Autowired
    private RcaEngine rcaEngine;

    @Test
    void rootCauseCaseShouldHitPlantedFault() {
        RcaCase rcaCase = rcaEngine.start("T-20260901-001");

        assertEquals("DONE", rcaCase.getStatus(), "案例应分析完成");
        assertNotNull(rcaCase.getConclusion());
        assertTrue(rcaCase.getConclusion().contains("C"), "结论应指出缺失的状态码C");
        assertTrue(rcaCase.getConclusion().contains("status_map"), "结论应定位到计费适配器映射表");

        Map<String, Object> detail = rcaEngine.caseDetail(rcaCase.getId());
        List<com.bemodel.rca.entity.RcaStep> steps =
                (List<com.bemodel.rca.entity.RcaStep>) detail.get("steps");
        assertEquals(7, steps.size(), "应产生7个分析步骤");

        // P1：张建国1笔取消未退费
        assertEquals(1, steps.get(1).getHitCount().intValue());

        // P2：LIS确认为C状态
        assertEquals(1, steps.get(2).getHitCount().intValue());
        assertTrue(steps.get(2).getResultJson().contains("\"C\""));

        // P3：裂缝=状态码C未映射
        assertEquals(1, steps.get(3).getHitCount().intValue());
        assertTrue(steps.get(3).getResultJson().contains("C"));

        // P4：全院5名患者/5笔/205元
        String p4Json = steps.get(4).getResultJson();
        assertTrue(p4Json.contains("5"), "影响患者数应为5: " + p4Json);
        assertTrue(p4Json.contains("205"), "影响金额应为205: " + p4Json);

        // 报告存在
        assertNotNull(detail.get("report"));
    }
}
