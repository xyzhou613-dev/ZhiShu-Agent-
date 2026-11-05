package com.bemodel.simulation;

import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.lab.LabSandboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 全链:起跑→轮询→DONE payload 契约+波及链层级+观察翻转+主库零写入(沙箱纪律同 lab) */
@SpringBootTest
class SimulationFullChainTest {

    @Autowired
    private SimulationService simulationService;
    @Autowired
    private LabSandboxService sandboxService;
    @Autowired
    private JdbcTemplate platform;
    @Autowired
    private DatasourceService datasourceService;

    @Test
    void fullChainStockCutProducesContractPayload() {
        Map<String, Object> started = simulationService.start("STOCK_CUT", Map.of());
        Long runId = ((Number) started.get("runId")).longValue();
        Map<String, Object> done = pollDone(runId);
        Map<String, Object> payload = (Map<String, Object>) done.get("payload");
        assertNotNull(payload, "DONE 必有 payload(完成时一次写)");

        // 终局契约键
        assertTrue(payload.containsKey("scenario"));
        assertTrue(payload.containsKey("action"));
        assertTrue(payload.containsKey("target"));
        assertTrue(payload.containsKey("affectedKeys"));
        assertTrue(payload.containsKey("steps"));
        assertTrue(payload.containsKey("observation"));
        assertTrue(payload.containsKey("diff"));
        assertTrue(payload.containsKey("elapsedMs"));

        // 施加目标:映射解析出 drug_stock.quantity,前值>100,后值=0
        Map<String, Object> target = (Map<String, Object>) payload.get("target");
        assertEquals("drug_stock", target.get("table"));
        assertEquals("quantity", target.get("column"));
        assertTrue(((Number) target.get("before")).intValue() > 100);
        assertEquals(0, ((Number) target.get("after")).intValue());

        // 波及链层级(无向扩散首达确定):出库→发药→医嘱→{就诊,费用}→患者/结算
        List<Map<String, Object>> steps = (List<Map<String, Object>>) payload.get("steps");
        Map<String, Integer> levelOf = new java.util.LinkedHashMap<>();
        steps.forEach(s -> levelOf.put((String) s.get("concept"), (Integer) s.get("level")));
        assertEquals(0, levelOf.get("DRUG_STOCK"));
        assertEquals(1, levelOf.get("STOCK_OUT"));
        assertEquals(2, levelOf.get("DISPENSE"));
        assertEquals(3, levelOf.get("MEDICAL_ORDER"));
        assertTrue(levelOf.containsKey("INP_VISIT"));
        assertTrue(levelOf.containsKey("FEE_DETAIL"));
        assertTrue(levelOf.containsKey("SETTLEMENT"));
        // 诚实断链:入库单无药品编码映射 → BLOCKED_GAP
        Map<String, Object> stockIn = steps.stream()
                .filter(s -> "STOCK_IN".equals(s.get("concept"))).findFirst().orElseThrow();
        assertEquals("BLOCKED_GAP", stockIn.get("status"));
        // 医嘱步:键族归一命中,证据带"项目编码"备注
        Map<String, Object> mo = steps.stream()
                .filter(s -> "MEDICAL_ORDER".equals(s.get("concept"))).findFirst().orElseThrow();
        assertEquals("MATCHED", mo.get("status"));
        assertTrue(mo.get("evidence").toString().contains("项目编码"));

        // 观察:低库存告警点亮+账实不符
        Map<String, Object> observation = (Map<String, Object>) payload.get("observation");
        Map<String, Object> after = (Map<String, Object>) observation.get("after");
        Map<String, Object> rule = (Map<String, Object>) after.get("rule");
        assertEquals(Boolean.FALSE, rule.get("pass"));
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) after.get("metrics");
        Map<String, Object> lowStock = metrics.stream()
                .filter(m -> "LOW_STOCK_COUNT".equals(m.get("code"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, lowStock.get("alert"));

        // diff 卡至少 3 条说人话句子
        List<String> items = (List<String>) ((Map<String, Object>) payload.get("diff")).get("items");
        assertTrue(items.size() >= 3, "diff.items 实际=" + items);
    }

    @Test
    void platformBusinessTablesUntouchedByRun() {
        String[] probes = {"demo_his.medical_order", "demo_his.fee_detail", "demo_his.inpatient",
                "demo_pharmacy.drug_stock", "demo_pharmacy.dispense_record", "demo_charge.settlement"};
        Map<String, Long> beforeCounts = new java.util.LinkedHashMap<>();
        for (String t : probes) {
            beforeCounts.put(t, platform.queryForObject("SELECT COUNT(*) FROM " + t, Long.class));
        }
        // 值探针:COUNT 抓不到 UPDATE,再钉住一个业务值,推演前后必须分毫不动
        Integer qtyBefore = platform.queryForObject(
                "SELECT quantity FROM demo_pharmacy.drug_stock WHERE drug_code='D006'", Integer.class);
        Map<String, Object> started = simulationService.start("STOCK_CUT", Map.of("drugCode", "D006"));
        pollDone(((Number) started.get("runId")).longValue());
        for (String t : probes) {
            assertEquals(beforeCounts.get(t),
                    platform.queryForObject("SELECT COUNT(*) FROM " + t, Long.class),
                    "主库业务表不得被推演改动: " + t);
        }
        assertEquals(qtyBefore,
                platform.queryForObject(
                        "SELECT quantity FROM demo_pharmacy.drug_stock WHERE drug_code='D006'", Integer.class),
                "主库业务值不得被推演改动: demo_pharmacy.drug_stock.quantity(D006)");
        // 沙箱里确实改了(D006=0)
        JdbcTemplate lab = datasourceService.jdbc(LabSandboxService.DS_LAB);
        Integer sandboxQty = lab.queryForObject(
                "SELECT quantity FROM drug_stock WHERE drug_code='D006'", Integer.class);
        assertEquals(0, sandboxQty);
    }

    @Test
    void scenariosEndpointShape() {
        List<Map<String, Object>> all = simulationService.scenarios();
        Map<String, Object> stockCut = all.stream()
                .filter(s -> "STOCK_CUT".equals(s.get("key"))).findFirst().orElseThrow();
        assertEquals("库存断供", stockCut.get("name"));
        assertEquals("DRUG_STOCK", stockCut.get("startConcept"));
        @SuppressWarnings("unchecked")
        Map<String, String> defaults = (Map<String, String>) stockCut.get("paramDefaults");
        assertEquals("D006", defaults.get("drugCode"));
    }

    @Test
    void resetRestoresSeedStock() {
        Map<String, Object> started = simulationService.start("STOCK_CUT", Map.of());
        // 先等本次推演落定:execute 内部也会 reset(异步),不等会与下面的 reset DROP 竞态
        pollDone(((Number) started.get("runId")).longValue());
        // reset 内部 DROP+重克隆,恢复 DataSeeder 种子值
        sandboxService.reset();
        JdbcTemplate lab = datasourceService.jdbc(LabSandboxService.DS_LAB);
        Integer qty = lab.queryForObject(
                "SELECT quantity FROM drug_stock WHERE drug_code='D006'", Integer.class);
        assertTrue(qty > 100, "恢复后库存应回到种子值,实测=" + qty);
    }

    private Map<String, Object> pollDone(Long runId) {
        for (int i = 0; i < 120; i++) {
            Map<String, Object> s = simulationService.status(runId);
            String status = (String) s.get("status");
            if ("DONE".equals(status) || "FAILED".equals(status)) {
                assertEquals("DONE", status, "推演失败: " + s.get("errorMsg"));
                return s;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("推演 60s 未落定, runId=" + runId);
    }
}
