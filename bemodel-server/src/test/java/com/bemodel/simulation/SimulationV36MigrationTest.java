package com.bemodel.simulation;

import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.mapper.MappingMapper;
import com.bemodel.ontology.entity.Metric;
import com.bemodel.ontology.mapper.MetricMapper;
import com.bemodel.simulation.entity.SimulationRun;
import com.bemodel.simulation.mapper.SimulationRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** V36 落地验证:运行表可读写、传导补缺映射 ACTIVE、观察探针种子在 */
@SpringBootTest
class SimulationV36MigrationTest {

    @Autowired
    private SimulationRunMapper runMapper;
    @Autowired
    private MappingMapper mappingMapper;
    @Autowired
    private MetricMapper metricMapper;

    @Test
    void simulationRunRoundtrip() {
        SimulationRun run = new SimulationRun();
        run.setScenario("STOCK_CUT");
        run.setStatus("QUEUED");
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);
        SimulationRun loaded = runMapper.selectById(run.getId());
        assertEquals("STOCK_CUT", loaded.getScenario());
        runMapper.deleteById(run.getId());
    }

    @Test
    void stockOutDrugCodeMappingActive() {
        Long cnt = mappingMapper.selectCount(new LambdaQueryWrapper<Mapping>()
                .eq(Mapping::getConceptCode, "STOCK_OUT")
                .eq(Mapping::getAttrCode, "drug_code")
                .eq(Mapping::getTableName, "stock_out")
                .eq(Mapping::getStatus, "ACTIVE"));
        assertEquals(1L, cnt, "V36 须补出 stock_out.drug_code→STOCK_OUT 的 ACTIVE 映射");
    }

    @Test
    void observationMetricSeedsPresent() {
        Metric low = metricMapper.selectOne(new LambdaQueryWrapper<Metric>()
                .eq(Metric::getMetricCode, "LOW_STOCK_COUNT"));
        assertNotNull(low);
        assertEquals("DS_LAB", low.getDsCode());
        assertEquals(0, low.getWarnThreshold());
        Metric turnover = metricMapper.selectOne(new LambdaQueryWrapper<Metric>()
                .eq(Metric::getMetricCode, "STOCK_TURNOVER"));
        assertNotNull(turnover);
        assertNull(turnover.getWarnThreshold());
    }
}
