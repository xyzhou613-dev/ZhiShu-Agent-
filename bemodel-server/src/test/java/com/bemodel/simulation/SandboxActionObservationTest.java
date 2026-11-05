package com.bemodel.simulation;

import com.bemodel.lab.LabSandboxService;
import com.bemodel.simulation.action.StockCutAction;
import com.bemodel.simulation.observe.ObservationService;
import com.bemodel.datasource.service.DatasourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 施加+观察沙箱集成:BeforeEach reset+ensureCloned 同 lab 纪律;断言翻转而非精确库存值 */
@SpringBootTest
class SandboxActionObservationTest {

    @Autowired
    private LabSandboxService sandboxService;
    @Autowired
    private DatasourceService datasourceService;
    @Autowired
    private StockCutAction stockCutAction;
    @Autowired
    private ObservationService observationService;
    @Autowired
    private SimulationScenarioRegistry registry;

    private JdbcTemplate lab;

    @BeforeEach
    void resetSandbox() {
        sandboxService.reset();
        lab = datasourceService.jdbc(LabSandboxService.DS_LAB);
    }

    @Test
    void applyRecordsBeforeAndCutsToZero() {
        StockCutAction.Applied applied = stockCutAction.apply(lab, "D006", 0);
        assertEquals("drug_stock", applied.table());
        assertEquals("quantity", applied.column());
        assertEquals("drug_code", applied.keyColumn());
        assertEquals("D006", applied.keyValue());
        assertTrue(applied.before() > 100, "种子库存应远大于 0,实测=" + applied.before());
        assertEquals(0, applied.after());
        Integer now = lab.queryForObject("SELECT quantity FROM drug_stock WHERE drug_code='D006'", Integer.class);
        assertEquals(0, now);
    }

    @Test
    void applyUnknownDrugFailsHonest() {
        assertThrows(com.bemodel.common.BizException.class,
                () -> stockCutAction.apply(lab, "NOPE", 0));
    }

    @Test
    void observationFlipsAfterCut() {
        Map<String, Object> before = observationService.snapshot(lab, "D006");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) before.get("rule")).get("pass"), "种子数据应账实相符");
        stockCutAction.apply(lab, "D006", 0);
        Map<String, Object> after = observationService.snapshot(lab, "D006");
        assertEquals(Boolean.FALSE, ((Map<?, ?>) after.get("rule")).get("pass"), "清零后应账实不符");
        @SuppressWarnings("unchecked")
        Map<String, Object> lowStock = ((java.util.List<Map<String, Object>>) after.get("metrics"))
                .stream().filter(m -> "LOW_STOCK_COUNT".equals(m.get("code"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, lowStock.get("alert"));
    }

    @Test
    void registryServesStockCutWithDefaults() {
        var s = registry.get("STOCK_CUT");
        assertEquals("DRUG_STOCK", s.startConcept());
        assertEquals("D006", s.paramDefaults().get("drugCode"));
        assertEquals(List.of("药品编码", "项目编码"), s.keyFamily());
        assertThrows(com.bemodel.common.BizException.class, () -> registry.get("NOPE"));
    }
}
