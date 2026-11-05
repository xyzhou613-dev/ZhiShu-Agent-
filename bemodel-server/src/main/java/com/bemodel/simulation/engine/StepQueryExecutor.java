package com.bemodel.simulation.engine;

import java.util.List;
import java.util.Map;

/** 传导步查询出口:生产实现落 DS_LAB,单测注入假实现——引擎保持纯逻辑零 Spring */
@FunctionalInterface
public interface StepQueryExecutor {

    List<Map<String, Object>> query(String sql, List<Object> params);
}
