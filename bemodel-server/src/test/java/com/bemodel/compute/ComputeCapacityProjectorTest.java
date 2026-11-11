package com.bemodel.compute;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ComputeCapacityProjectorTest {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=ComputeRuleEvaluatorTest.JSON;
    private ComputeTargetSelectorTest source;
    private ComputeEvidenceService.Detail captured;
    private ObjectNode ledger,proposal;
    private ComputeRuleRunner.Snapshot rules;
    private void fixture() {
        source=new ComputeTargetSelectorTest();var original=source.fixture(2); ledger=source.ledger;
        captured=new ComputeEvidenceService.Detail(new ComputeEvidenceService.Snapshot(1,original.snapshot().asOf(),4,1,1,"admin",true,
                JSON.createObjectNode().put("usable",true)),original.evidence(),false);
        source.settings.put("trainingPriority",2).put("residentPriority",1);
        ((ObjectNode)ledger.path("node")).put("memory_capacity_bytes",1000).put("memory_reserved_bytes",100)
                .put("temporary_capacity_bytes",1000).put("persistent_capacity_bytes",1000);
        for(JsonNode c:ledger.path("cards"))((ObjectNode)c).put("vram_bytes",80).put("gpu_index",c.path("id").asLong()-1);
        var spec=JSON.createObjectNode().put("memoryBytes",80).put("vramBytesPerGpu",10).put("gpuInterconnect","NONE");
        ((ObjectNode)ledger.path("request")).put("type_code","TRAINING").set("requested_resource_spec",spec);
        ledger.putObject("activeJob").put("id",1).put("status","PENDING").put("type_code","TRAINING").put("effective_gpu_count",2).set("effective_resource_spec",spec.deepCopy());
        var baselines=ledger.putArray("deploymentBaselines");var revisions=ledger.putArray("deploymentRevisions");
        for(int id=1;id<=3;id++) {
            var b=JSON.createObjectNode().put("id",id).put("group_id",id).put("preemptible",true)
                    .put("memory_bytes",id==1?100:id==2?200:50).put("temporary_bytes",0).put("persistent_bytes",0);
            baselines.add(b);var rev=b.deepCopy().put("deployment_id",id);revisions.add(rev);
        }
        var own=JSON.createObjectNode().put("id",1).put("job_id",1).put("node_id",1).put("group_id",2).put("status","PENDING")
                .put("memory_bytes",80).put("temporary_bytes",0).put("persistent_bytes",0);
        ledger.withArray("allocations").add(own);
        ledger.withArray("allocations").addObject().put("id",2).put("group_id",4).put("status","OCCUPIED").put("memory_bytes",60).put("temporary_bytes",0).put("persistent_bytes",0);
        ledger.putArray("jobAllocations").add(own.deepCopy());ledger.putArray("allocationCards");
        var versions=new ArrayList<ComputeRuleRunner.ExecutionRule>();
        for(String code:ComputeRuleEvaluator.CODES) {
            JsonNode def=ComputeRuleEvaluatorTest.FIXTURES.get(code).path("definition");
            versions.add(new ComputeRuleRunner.ExecutionRule(versions.size()+1,code,3,7,def.path("stage").asText(),def));
        }
        rules=new ComputeRuleRunner.Snapshot(4,1,"v1",ComputeRuleEvaluator.VERSION,ComputeVersions.FORMULAS,versions);
        proposal=JSON.createObjectNode().put("mode","PLAN");
    }
    private ComputeCapacityProjector.Projection run(){return new ComputeCapacityProjector(JSON).project(captured,rules,source.settings,proposal);}
    private ComputeCapacityProjector.Check check(ComputeCapacityProjector.Projection p,String code){return p.checks().stream().filter(c->code.equals(c.rule().ruleCode())).findFirst().orElseThrow();}

    @Test void countsOwnReservationOnceAndOnlyRemovesAnEligiblePauseConditionally() {
        fixture();var p=run();assertEquals(490,p.output().path("projectedBytes").path("memoryBytes").asLong());
        assertEquals("PASS",p.overallResult().name());assertEquals(60,p.output().path("effectiveReservationBytes").path("memoryBytes").asLong());
        assertEquals("UNKNOWN",check(p,"R-03").facts().path("groupInterconnect").asText());
        proposal.put("pausedDeploymentId",2);p=run();
        assertEquals(290,p.output().path("projectedBytes").path("memoryBytes").asLong());
        assertTrue(p.output().path("requiresStopConfirmation").asBoolean());
        ((ObjectNode)ledger.path("deploymentBaselines").get(1)).put("preemptible",false);
        p=run();assertEquals(490,p.output().path("projectedBytes").path("memoryBytes").asLong());
        assertEquals("FAIL",check(p,"R-13").result().name());
    }
    @Test void restoresWithoutDroppingLiveAllocationsOrCountingTheOldBaselineTwice() {
        fixture();proposal.put("mode","RESTORE").put("restoreRevisionId",2);
        var p=run();assertEquals(490,p.output().path("projectedBytes").path("memoryBytes").asLong());
        assertEquals(140,p.output().path("effectiveReservationBytes").path("memoryBytes").asLong());
        assertEquals("PASS",check(p,"R-15").result().name());assertEquals("UNKNOWN",p.feasibility());
        ((ObjectNode)source.observations.get(5).values()).put("healthy",false);
        assertEquals("RECOVERY_UNHEALTHY",check(run(),"R-15").reasonCode());
    }
    @Test void unknownInterconnectAndVramNeverPassAndCapacityShortageIsRecorded() {
        fixture();((ObjectNode)ledger.path("activeJob").path("effective_resource_spec")).put("gpuInterconnect","NVLINK");
        assertEquals("UNKNOWN_INTERCONNECT",check(run(),"R-03").reasonCode());
        ((ObjectNode)ledger.path("activeJob").path("effective_resource_spec")).remove("vramBytesPerGpu");
        assertEquals("UNKNOWN_VRAM_REQUIREMENT",check(run(),"R-03").reasonCode());
        assertTrue(check(run(),"R-03").facts().path("vramBytesPerGpu").isNull());
        fixture();((ObjectNode)ledger.path("node")).put("memory_capacity_bytes",500);
        assertEquals("QUEUE_NO_CAPACITY",check(run(),"R-02").reasonCode());
    }
    @Test void rejectsForeignOrClosedOwnAllocationsAndArithmeticOverflow() {
        fixture();((ObjectNode)ledger.path("jobAllocations").get(0)).put("node_id",2);
        assertThrows(BizException.class,this::run);
        fixture();((ObjectNode)ledger.path("jobAllocations").get(0)).put("status","RELEASED");assertThrows(BizException.class,this::run);
        fixture();((ObjectNode)ledger.path("deploymentBaselines").get(0)).put("memory_bytes",Long.MAX_VALUE);
        assertThrows(BizException.class,this::run);
    }

    @Test void selectsEnoughIndividuallyCompatibleCardsInsteadOfAddingVramAcrossCards() {
        fixture();ledger.withArray("jobAllocations").removeAll();ledger.withArray("allocations").remove(0);
        ((ObjectNode)ledger.path("activeJob")).put("effective_gpu_count",3);
        ((ObjectNode)ledger.path("cards").get(0)).put("vram_bytes",8);
        var projected=run();
        assertEquals("PASS",check(projected,"R-03").result().name());
        assertFalse(projected.output().path("usedGpuIds").toString().contains("1"));
        ((ObjectNode)ledger.path("cards").get(1)).put("vram_bytes",8);
        assertEquals("QUEUE_RESOURCE_SHORT",check(run(),"R-03").reasonCode());
        fixture();ledger.withArray("jobAllocations").removeAll();ledger.withArray("allocations").remove(0);
        ((ObjectNode)ledger.path("activeJob")).put("effective_gpu_count",3);
        ((ObjectNode)ledger.path("activeJob").path("effective_resource_spec")).remove("vramBytesPerGpu");
        ((ObjectNode)source.observations.get(0).values()).put("healthy",false);
        assertEquals("PASS",check(run(),"R-14").result().name());
        assertEquals("UNKNOWN_VRAM_REQUIREMENT",check(run(),"R-03").reasonCode());
    }
}
