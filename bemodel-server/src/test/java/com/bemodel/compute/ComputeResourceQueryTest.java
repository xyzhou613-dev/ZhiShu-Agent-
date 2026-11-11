package com.bemodel.compute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeResourceQueryTest {
    private static final long G=1073741824L;
    @Test void showsHeldPendingCardsResponsibilityAndPointerMismatchSeparately() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var query=new ComputeGroupQuery(new ComputeDatabase.Store(db.business(),null));
            var at=Instant.parse("2026-11-06T10:00:00Z");
            var b=query.groups("a800-demo",at).stream().filter(g->g.code().equals("B")).findFirst().orElseThrow();
            assertEquals(List.of("GPU-DEMO-4","GPU-DEMO-5"),b.holderUsage().usedGpuUuids());
            assertTrue(b.pendingUsage().usedGpuUuids().isEmpty());
            assertEquals(2,b.pendingUsage().retainedGpuUuids().size());
            assertEquals(1,b.pendingRequest().requestId());assertEquals(2,b.restoreResponsibility().revisionId());
            assertTrue(b.ledgerConsistent());assertFalse(b.ledgerAvailable());
            db.business().update("UPDATE gpu_group SET pending_allocation_id=NULL,current_hold_id=NULL WHERE id=2");
            b=query.groups("a800-demo",at).stream().filter(g->g.code().equals("B")).findFirst().orElseThrow();
            assertNotNull(b.holder());assertNotNull(b.reservation());assertFalse(b.ledgerConsistent());assertFalse(b.directlyAllocatable());assertTrue(b.reasons().contains("GROUP_LEDGER_MISMATCH"));
            // Keep a long validity interval, but age still blocks it.
            db.business().update("UPDATE resource_observation SET valid_until='2026-11-07' WHERE subject_id=8");
            var flex=query.groups("a800-demo",at.plusSeconds(31)).stream().filter(g->g.code().equals("FLEX")).findFirst().orElseThrow();
            assertTrue(flex.ledgerAvailable());assertFalse(flex.directlyAllocatable());
        }
    }
    @Test void pendingAndOccupiedCountOnceAndReleasedStopsCounting() throws Exception {
        try(var db=ComputeMysqlTestDatabase.create()) {
            var q=new ComputeCapacityQuery(new ComputeDatabase.Store(db.business(),null),ComputeRuleEvaluatorTest.JSON);
            var before=q.preview("a800-demo",1L,2L,2L);
            assertEquals(new ComputeResourceMath.Bytes(72*G,0,0),before.get("projectedBytes"));
            assertEquals(new ComputeResourceMath.Bytes(0,0,0),before.get("effectiveReservationBytes"));
            assertEquals(false,before.get("authorizesExecution"));
            var restore=(Map<?,?>)before.get("restore");assertEquals(new ComputeResourceMath.Bytes(88*G,0,0),restore.get("projectedBytes"));
            db.business().update("INSERT INTO allocation_gpu VALUES(1,5),(1,6)");db.business().update("UPDATE gpu_allocation SET status='OCCUPIED' WHERE id=1");
            assertEquals(before.get("projectedBytes"),q.preview("a800-demo",1L,2L,null).get("projectedBytes"));
            assertThrows(RuntimeException.class,()->q.preview("a800-demo",1L,3L,null));
            db.business().update("UPDATE gpu_allocation SET status='RELEASED' WHERE id=1");
            assertEquals(new ComputeResourceMath.Bytes(56*G,0,0),q.preview("a800-demo",null,null,null).get("projectedBytes"));
            assertThrows(RuntimeException.class,()->q.preview("a800-demo",1L,null,null));
        }
    }
}
