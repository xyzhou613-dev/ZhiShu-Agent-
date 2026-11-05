package com.bemodel.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** CircuitState golden（spec §6 四夹具，注入毫秒时钟零 sleep） */
class CircuitStateTest {
    private static final long T0 = 1_000_000L;

    @Test
    void 连续失败一次不开路_两次开路() {
        CircuitState cs = new CircuitState();
        cs.recordFailure(T0);
        assertTrue(cs.isUsable(T0 + 1), "阈 2：单次失败不熔断");
        cs.recordFailure(T0 + 2);
        assertFalse(cs.isUsable(T0 + 3), "连续失败达阈 2 即开路");
    }

    @Test
    void 成功复位计数与开路() {
        CircuitState cs = new CircuitState();
        cs.recordFailure(T0);
        cs.recordSuccess();
        cs.recordFailure(T0 + 1);
        assertTrue(cs.isUsable(T0 + 2), "成功后计数复位：新的一次失败不熔断");
    }

    @Test
    void 冷却300s后半开放行() {
        CircuitState cs = new CircuitState();
        cs.recordFailure(T0);
        cs.recordFailure(T0);
        assertFalse(cs.isUsable(T0 + 299_999), "冷却期内不可用");
        assertTrue(cs.isUsable(T0 + 300_000), "冷却满 300s 半开放行（放一次真实调用探路）");
    }

    @Test
    void 半开失败_重开续冷却() {
        CircuitState cs = new CircuitState();
        cs.recordFailure(T0);
        cs.recordFailure(T0 + 1);
        long probeAt = T0 + CircuitState.COOLDOWN_MS;
        cs.recordFailure(probeAt);
        assertFalse(cs.isUsable(probeAt + 299_999), "半开失败=重开，续冷却 300s");
        assertTrue(cs.isUsable(probeAt + 300_000));
    }
}
