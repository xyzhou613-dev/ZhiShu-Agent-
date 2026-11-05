package com.bemodel.simulation;

import com.bemodel.common.BizException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推演场景注册表:引擎通用,域扩展=注册新场景(键族+施加参数),spec §5 注册表模式。
 * keyFamily 首元素为 canonical 键名;「项目编码」入「药品编码」族=口径漂移的键级归一
 * (医嘱表把药品编码存进项目编码列,证据行会如实备注这层归一)。
 */
@Component
public class SimulationScenarioRegistry {

    public record Scenario(String key, String name, String description, String startConcept,
                           List<String> keyFamily, Map<String, String> paramDefaults) {}

    private final Map<String, Scenario> scenarios = new LinkedHashMap<>();

    public SimulationScenarioRegistry() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("drugCode", "D006");
        defaults.put("quantity", "0");
        Scenario stockCut = new Scenario("STOCK_CUT", "库存断供",
                "把某药品的库存直接改小(默认清零),沿本体看波及链:哪些医嘱/患者/发药/费用被牵连,巡检项与账实规则怎么翻转。",
                "DRUG_STOCK", List.of("药品编码", "项目编码"), defaults);
        scenarios.put(stockCut.key(), stockCut);
    }

    public Scenario get(String key) {
        Scenario s = scenarios.get(key);
        if (s == null) {
            throw new BizException("没有这个推演场景: " + key);
        }
        return s;
    }

    public List<Scenario> all() {
        return List.copyOf(scenarios.values());
    }
}
