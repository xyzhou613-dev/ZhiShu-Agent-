package com.bemodel.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** RoutePlanner golden（spec §6 七夹具，纯逻辑零 Spring 零时钟） */
class RoutePlannerGoldenTest {

    private final ProviderEndpoint primary =
            new ProviderEndpoint("primary", "http://primary.example", "k1", "deepseek-v4-flash");
    private final ProviderEndpoint backup =
            new ProviderEndpoint("backup", "http://backup.example", "k2", "deepseek-v4-flash");

    @Test
    void 默认_主先备后() {
        assertEquals(List.of(primary, backup),
                RoutePlanner.plan("CS_REPLY", primary, backup, Map.of(), false, false));
    }

    @Test
    void callPrimaryRoute覆盖_backup值备先主后() {
        // spec DoD 6：LAB_A 可定向备路
        assertEquals(List.of(backup, primary), RoutePlanner.plan(
                "LAB_A", primary, backup, Map.of("LAB_A", "backup"), false, false));
    }

    @Test
    void callPrimaryRoute值非法_回退主先() {
        assertEquals(List.of(primary, backup), RoutePlanner.plan(
                "LAB_A", primary, backup, Map.of("LAB_A", "secondary"), false, false));
    }

    @Test
    void 备路不存在_退化单候选() {
        assertEquals(List.of(primary), RoutePlanner.plan("CS_REPLY", primary, null, Map.of(), false, false));
    }

    @Test
    void 主路开路_只剩余备() {
        assertEquals(List.of(backup), RoutePlanner.plan("CS_REPLY", primary, backup, Map.of(), true, false));
    }

    @Test
    void 备路开路_只剩余主() {
        assertEquals(List.of(primary), RoutePlanner.plan("CS_REPLY", primary, backup, Map.of(), false, true));
    }

    @Test
    void 两路全开_原序返回_死马当活马医() {
        assertEquals(List.of(primary, backup),
                RoutePlanner.plan("CS_REPLY", primary, backup, Map.of(), true, true));
    }
}
