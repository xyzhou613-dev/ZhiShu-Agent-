package com.bemodel.compute;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComputeGroupQueryTest {
    private final Instant sampled = Instant.parse("2026-11-06T10:00:00Z");
    private final Instant expires = sampled.plusSeconds(30);

    @Test
    void holderAndPendingReservationRemainDistinct() {
        var holder = new ComputeGroupQuery.Holder("DEPLOYMENT", 2, "模型 B");
        var pending = new ComputeGroupQuery.Reservation(1, 1, 1);
        var group = ComputeGroupQuery.evaluate("B", holder, pending, List.of(card(true, true)), sampled);
        assertEquals(holder, group.holder());
        assertEquals(pending, group.reservation());
        assertFalse(group.directlyAllocatable());
        assertEquals(List.of("GROUP_HELD", "GROUP_RESERVED", "GPU_OCCUPIED"), group.reasons());
    }

    @Test
    void expiredUnknownAndUnhealthyObservationsBlockAllocation() {
        assertTrue(ComputeGroupQuery.evaluate("FLEX", null, null, List.of(card(true, false)), expires)
                .directlyAllocatable());
        assertEquals(List.of("GPU_EVIDENCE_UNAVAILABLE"), ComputeGroupQuery.evaluate("FLEX", null, null,
                List.of(card(true, false)), expires.plusNanos(1)).reasons());
        assertEquals(List.of("GPU_EVIDENCE_UNAVAILABLE"), ComputeGroupQuery.evaluate("FLEX", null, null,
                List.of(card(null, false)), sampled).reasons());
        assertEquals(List.of("GPU_UNHEALTHY"), ComputeGroupQuery.evaluate("FLEX", null, null,
                List.of(card(false, false)), sampled).reasons());
    }

    private ComputeGroupQuery.Gpu card(Boolean healthy, boolean occupied) {
        return new ComputeGroupQuery.Gpu("GPU-DEMO", 7, 85899345920L,
                new ComputeGroupQuery.Observation("fixture", sampled, expires, "OK", healthy, occupied));
    }
}
