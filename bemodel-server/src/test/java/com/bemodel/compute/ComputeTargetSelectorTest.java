package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComputeTargetSelectorTest {
    static final com.fasterxml.jackson.databind.ObjectMapper JSON = ComputeRuleEvaluatorTest.JSON;
    private final ComputeTargetSelector selector = new ComputeTargetSelector(JSON);
    private final Instant at = Instant.parse("2026-11-06T10:00:10Z");
    ObjectNode ledger;
    ObjectNode settings;
    List<ComputeEvidenceService.Evidence> observations;

    ComputeEvidenceService.Detail fixture(int count) {
        ledger = JSON.createObjectNode(); settings = JSON.createObjectNode().put("nodeKey", "a800-demo");
        ledger.putObject("node").put("id", 1).put("node_key", "a800-demo");
        ledger.putObject("request").put("requested_gpu_count", count);
        var groups = ledger.putArray("groups"); var cards = ledger.putArray("cards"); var published = settings.putArray("groups");
        ledger.putArray("holds"); ledger.putArray("allocations");
        observations = new ArrayList<>(); int cardId=0;
        for (String code : List.of("A","B","C","FLEX")) {
            int id=groups.size()+1, size=Map.of("A",4,"B",2,"C",1,"FLEX",1).get(code);
            groups.addObject().put("id",id).put("group_code",code).putNull("current_hold_id").putNull("pending_allocation_id");
            published.addObject().put("id",id).put("group_code",code).put("cards",size);
            for (int i=0;i<size;i++) {
                cardId++; cards.addObject().put("id",cardId).put("group_id",id);
                var values=JSON.createObjectNode().put("subjectKind","GPU").put("subjectId",cardId)
                        .put("usable",true).put("healthy",true).put("occupied",false).put("maxAgeSeconds",30);
                observations.add(new ComputeEvidenceService.Evidence(cardId+10,"A800_DEMO_FIXTURE","obs:"+cardId,(long)cardId,at.minusSeconds(10),at.plusSeconds(20),values));
            }
        }
        var evidence=new ArrayList<ComputeEvidenceService.Evidence>();
        evidence.add(new ComputeEvidenceService.Evidence(1,"PLATFORM_LEDGER","node:1",null,at,at,ledger));
        evidence.addAll(observations);
        return new ComputeEvidenceService.Detail(new ComputeEvidenceService.Snapshot(1,at,4,1,1,"admin",true,JSON.createObjectNode()),evidence,false);
    }
    private ComputeRuleRunner.Snapshot rules() {
        var r=new ComputeRuleRunner.ExecutionRule(10,"R-04",3,7,"PLANNING",ComputeRuleEvaluatorTest.FIXTURES.get("R-04").path("definition"));
        return new ComputeRuleRunner.Snapshot(4,1,"v1",ComputeRuleEvaluator.VERSION,ComputeVersions.FORMULAS,List.of(r));
    }
    private ComputeTargetSelector.Choice choose(ComputeEvidenceService.Detail e) { return selector.select(e,rules(),settings); }

    @Test void followsTheV07CardCountTableAndNeverBorrowsALargerGroup() {
        Map<Integer,String> expected=Map.of(1,"FLEX",2,"B",3,"A",4,"A");
        expected.forEach((count,target)->assertEquals(target,choose(fixture(count)).targetGroupCode()));
        var tooLarge=choose(fixture(5));
        assertNull(tooLarge.targetGroupId()); assertEquals("WAITING",tooLarge.selectionStatus());
        assertEquals("QUEUE_NO_TARGET",tooLarge.reasonCode());
        var e=fixture(2);
        ((ObjectNode)ledger.path("groups").get(1)).put("pending_allocation_id",9);
        ledger.withArray("allocations").addObject().put("id",9).put("group_id",2).put("status","PENDING");
        var busy=choose(e);
        assertEquals("B",busy.targetGroupCode()); assertEquals("WAITING",busy.selectionStatus());
        assertTrue(busy.output().path("blockingReasons").toString().contains("GROUP_RESERVED"));
    }

    @Test void singleCardFallsBackToBForEveryFlexBlockerAndUsesFrozenEvidence() {
        for (String field : List.of("healthy","usable","occupied")) {
            var e=fixture(1); ((ObjectNode)observations.getLast().values()).put(field,"occupied".equals(field));
            assertEquals("B",choose(e).targetGroupCode(),field);
        }
        var e=fixture(1);
        ((ObjectNode)ledger.path("groups").get(3)).put("current_hold_id",99);
        assertEquals("B",choose(e).targetGroupCode());
        e=fixture(1); ((ObjectNode)ledger.path("groups").get(3)).put("pending_allocation_id",99);
        assertEquals("B",choose(e).targetGroupCode());
        e=fixture(1);
        ((ObjectNode)observations.getLast().values()).put("maxAgeSeconds",1);
        var choice=choose(e);
        assertEquals("B",choice.targetGroupCode());
        assertTrue(choice.evidenceIds().contains(observations.getLast().id()));
        assertTrue(choice.evidenceIds().contains(1L));
    }

    @Test void usesEffectiveJobCountAndKeepsPreemptionSeparateFromAReservation() {
        var e=fixture(4);
        ledger.putObject("activeJob").put("id",22).put("effective_gpu_count",2);
        ((ObjectNode)ledger.path("groups").get(1)).put("current_hold_id",8);
        ledger.withArray("holds").addObject().put("id",8).put("group_id",2).put("deployment_id",2);
        var selected=choose(e);
        assertEquals("B",selected.targetGroupCode()); assertEquals("JOB",selected.specBasis()); assertEquals(22L,selected.jobId());
        assertEquals("PREEMPTION_CHECK_REQUIRED",selected.selectionStatus());
        assertFalse(selected.output().path("authorizesExecution").asBoolean(true));
        assertFalse(selected.output().path("queuePersisted").asBoolean(true));
        ((ObjectNode)ledger.path("groups").get(1)).put("pending_allocation_id",9);
        ledger.withArray("allocations").addObject().put("id",9).put("group_id",2).put("status","PENDING");
        assertEquals("WAITING",choose(e).selectionStatus());
    }

    @Test void rejectsChangedPublishedLayoutsAndInvalidCounts() {
        var e=fixture(1); settings.put("nodeKey","another-node");
        var original=e; assertThrows(BizException.class,()->choose(original));
        e=fixture(1); ((ObjectNode)settings.path("groups").get(0)).put("id",99);
        var changed=e; assertThrows(BizException.class,()->choose(changed));
        e=fixture(0); var invalid=e; assertThrows(BizException.class,()->choose(invalid));
    }

    @Test void aFaultyUnusedCardDoesNotPreventAThreeCardTarget() {
        var e=fixture(3);
        ((ObjectNode)observations.get(3).values()).put("healthy",false);
        var selected=choose(e);
        assertEquals("A",selected.targetGroupCode());
        assertEquals("TARGET_SELECTED",selected.selectionStatus());
        ((ObjectNode)observations.get(2).values()).put("healthy",false);
        assertEquals("WAITING",choose(e).selectionStatus());
        assertEquals("A",choose(e).targetGroupCode());
    }
}
