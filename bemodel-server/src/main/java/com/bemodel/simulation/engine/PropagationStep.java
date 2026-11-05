package com.bemodel.simulation.engine;

import java.util.List;
import java.util.Map;

/** 传导步(spec §4 契约):一步=波及链上一个概念层的查询与证据 */
public record PropagationStep(String concept, String conceptName, int level, String status,
                              Map<String, Object> evidence, String sqlSummary, int hits,
                              List<Map<String, Object>> sample) {

    public static final String MATCHED = "MATCHED";
    public static final String EMPTY = "EMPTY";
    public static final String BLOCKED_GAP = "BLOCKED_GAP";
}
