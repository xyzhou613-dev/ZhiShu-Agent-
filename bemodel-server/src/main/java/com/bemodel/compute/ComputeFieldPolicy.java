package com.bemodel.compute;

/**
 * 单个响应字段的敏感标注（M-16）。{@code path} 使用点号路径，数组元素用 {@code []} 表示，
 * 例如 {@code cards[].uuid}、{@code request.launchSpec}。
 */
record ComputeFieldPolicy(String resource, String path, ComputeSensitivity sensitivity, boolean agentVisible,
                          String note) {
    static ComputeFieldPolicy of(String resource, String path, ComputeSensitivity sensitivity,
                                 boolean agentVisible, String note) {
        return new ComputeFieldPolicy(resource, path, sensitivity, agentVisible, note);
    }
}
