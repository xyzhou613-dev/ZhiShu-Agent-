package com.bemodel.llm;

/**
 * 端点熔断状态（借鉴 4）：内存态，每端点一实例，重启复位。无定时器——
 * isUsable(now) 惰性判定：开路且冷却未满=不可用；冷却已过=放行一次真实调用探路（半开）。
 * 连续失败阈 2 开路；半开探路失败=重开续冷却（计数续走仍 ≥ 阈）。不做定时探活（spec 拍板）。
 */
class CircuitState {

    static final int FAILURE_THRESHOLD = 2;
    static final long COOLDOWN_MS = 300_000L;

    private int consecutiveFailures;
    private long openedAtMs;

    synchronized boolean isUsable(long nowMs) {
        return openedAtMs == 0 || nowMs - openedAtMs >= COOLDOWN_MS;
    }

    synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openedAtMs = 0;
    }

    synchronized void recordFailure(long nowMs) {
        consecutiveFailures++;
        if (consecutiveFailures >= FAILURE_THRESHOLD) {
            openedAtMs = nowMs;
        }
    }
}
