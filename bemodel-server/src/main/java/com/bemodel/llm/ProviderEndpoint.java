package com.bemodel.llm;

/**
 * 路由端点视图（借鉴 4）：name 取 "primary"|"backup"，原样写入 bm_llm_log.provider 列。
 * 纯值对象——端点由 DeepSeekProperties 组装，RoutePlanner/CircuitState 只认这个形状。
 */
record ProviderEndpoint(String name, String baseUrl, String apiKey, String model) {

    boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
