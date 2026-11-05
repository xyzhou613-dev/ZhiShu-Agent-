package com.bemodel.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据治理扫描验证：探针真实执行，
 * GOV-004（检验状态字典跨库一致）必须命中 C（与根因案例同一裂缝），
 * GOV-007（库存账实相符）必须通过（种子进销存自洽）。
 */
@SpringBootTest
class GovServiceTest {

    @Autowired
    private GovService govService;

    @Test
    @SuppressWarnings("unchecked")
    void scanShouldHitDictGapAndKeepStockBalanced() {
        Map<String, Object> scan = govService.scan();
        assertTrue(((Number) scan.get("ruleCount")).intValue() >= 10, "至少10条治理规则");
        assertTrue(((Number) scan.get("durationMs")).longValue() >= 0, "实测耗时落库");
        assertTrue(((Number) scan.get("issueCount")).intValue() >= 1, "至少有字典裂缝问题");

        var issues = govService.issues(1, 50).getList();
        // GOV-004：lab_apply 的 C 状态在 HIS status_map 中无映射（与客诉根因同一裂缝）
        var dict = issues.stream().filter(i -> "GOV-004".equals(i.getRuleCode())).findFirst();
        assertTrue(dict.isPresent(), "GOV-004 字典一致性必须命中");
        assertTrue(dict.get().getHitCount() > 0);
        assertTrue(dict.get().getSampleJson().contains("C"), "命中值应含 C");

        // GOV-007：库存=Σ入-Σ出，种子自洽必须通过（不在问题清单里）
        assertTrue(issues.stream().noneMatch(i -> "GOV-007".equals(i.getRuleCode())),
                "库存账实相符不应产生问题");

        // 总览与表治理画像
        Map<String, Object> overview = govService.overview();
        assertTrue(((Number) overview.get("tableCount")).intValue() >= 40, "V15后物理表应≥40");
        assertTrue(((Number) overview.get("coverage")).doubleValue() > 0, "应有映射覆盖率");
        assertNotNull(overview.get("qualityScore"), "应有质量分");

        List<Map<String, Object>> tables = govService.tables();
        assertFalse(tables.isEmpty());
        assertTrue(tables.stream().anyMatch(t -> "lab_apply".equals(t.get("tableName"))
                && ((Number) t.get("issueCount")).intValue() > 0), "lab_apply 应挂问题数");
        assertTrue(tables.stream().anyMatch(t -> "drug_stock".equals(t.get("tableName"))),
                "进销存新表应被治理覆盖");
    }
}
