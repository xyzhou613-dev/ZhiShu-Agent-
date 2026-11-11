package com.bemodel.compute;

import com.bemodel.modeling.entity.Rule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Consumer;

import static com.bemodel.compute.ComputeRuleEvaluator.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class ComputeRuleEvaluatorTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String, JsonNode> FIXTURES = fixtures();
    private final ComputeRuleEvaluator evaluator = new ComputeRuleEvaluator();

    static Map<String, JsonNode> fixtures() {
        try (var in = ComputeRuleEvaluatorTest.class.getResourceAsStream("/compute/m02-rules.json")) {
            Map<String, JsonNode> map = new LinkedHashMap<>();
            for (JsonNode row : JSON.readTree(in)) map.put(row.path("ruleCode").asText(), row);
            return map;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static Rule rule(String code) {
        JsonNode row = FIXTURES.get(code);
        Rule rule = new Rule();
        rule.setRuleCode(code); rule.setVersion(7); rule.setEngine("COMPUTE"); rule.setOwner("算力平台");
        rule.setConceptCode(row.path("conceptCode").asText()); rule.setName(row.path("name").asText());
        rule.setStatus("DRAFT"); rule.setExprJson(row.path("definition").toString());
        return rule;
    }

    private ComputeRuleEvaluator.Result run(String code, Consumer<ObjectNode> edit) {
        JsonNode def = FIXTURES.get(code).path("definition");
        ObjectNode facts = def.path("sampleFacts").deepCopy();
        edit.accept(facts);
        return evaluator.evaluate(rule(code), def, facts);
    }
    private void reason(String code, String expected, Consumer<ObjectNode> edit) {
        var result = run(code, edit);
        assertEquals(FAIL, result.result(), result.toString());
        assertEquals(expected, result.reasonCode());
    }
    private ObjectNode obj(ObjectNode root, String key) { return (ObjectNode) root.path(key); }

    @Test void everyRuleHasAWorkingSampleAndNeverAuthorizesAnOperation() {
        assertEquals(14, FIXTURES.size());
        assertEquals(new HashSet<>(ComputeRuleEvaluator.CODES), FIXTURES.keySet());
        assertFalse(FIXTURES.containsKey("R-09"));
        for (String code : ComputeRuleEvaluator.CODES) {
            var result = run(code, f -> {});
            assertEquals(PASS, result.result(), code + result);
            assertTrue(result.dryRun()); assertFalse(result.authorizesExecution());
            assertEquals(7, result.ruleVersion());
            assertEquals(ComputeRuleEvaluator.VERSION, result.evaluatorVersion());
        }
    }

    @Test void specificationRejectsInvalidAndOversizedRequests() {
        reason("R-01", "DENY_SPEC", f -> f.put("gpuCount", 0));
        reason("R-01", "DENY_SPEC", f -> f.remove("resourceSpec"));
        reason("R-01", "DENY_SPEC", f -> obj(f, "resourceSpec").put("memoryBytes", -1));
        reason("R-01", "QUEUE_NO_TARGET", f -> f.put("gpuCount", 5));
        assertEquals(PASS, run("R-01", f -> f.put("gpuCount", 4)).result());
    }

    @Test void selectionKeepsSmallTasksOffGroupAAndProtectsFlexReservations() {
        assertEquals("FLEX", run("R-04", f -> {}).output().get("targetGroup"));
        for (String key : List.of("held", "reserved", "occupied"))
            assertEquals("B", run("R-04", f -> obj(f, "flex").put(key, true)).output().get("targetGroup"));
        for (String key : List.of("healthy", "evidenceFresh"))
            assertEquals("B", run("R-04", f -> obj(f, "flex").put(key, false)).output().get("targetGroup"));
        assertEquals("B", run("R-04", f -> { f.put("gpuCount", 2); f.remove("flex"); }).output().get("targetGroup"));
        for (int count : List.of(3, 4)) assertEquals("A", run("R-04", f -> f.put("gpuCount", count)).output().get("targetGroup"));
        reason("R-04", "QUEUE_NO_TARGET", f -> f.put("gpuCount", 5));
    }

    @Test void projectionUsesPostPauseComponentsAndDetectsOverflow() {
        assertEquals(PASS, run("R-02", f -> {
            for (String key : List.of("memoryBytes", "temporaryBytes", "persistentBytes")) {
                obj(f,"continuingDeploymentBytes").put(key,0); obj(f,"effectiveReservationBytes").put(key,0);
                obj(f,"newReservationBytes").put(key,80); obj(f,"capacityBytes").put(key,80);
            }
        }).result());
        reason("R-02", "QUEUE_NO_CAPACITY", f -> obj(f,"newReservationBytes").put("memoryBytes", 128L*1024*1024*1024));
        assertEquals(ERROR, run("R-02", f -> obj(f,"newReservationBytes").put("memoryBytes", Long.MAX_VALUE)).result());
        assertEquals(ERROR, run("R-02", f -> obj(f,"capacityBytes").put("memoryBytes", "128GB")).result());
    }

    @Test void eachGpuMustMeetVramAndInterconnectRequirements() {
        reason("R-03", "QUEUE_RESOURCE_SHORT", f -> f.putArray("selectedGpuVramBytes").add(40L*1024*1024*1024).add(40L*1024*1024*1024));
        reason("R-03", "QUEUE_RESOURCE_SHORT", f -> { f.put("requiredInterconnect", "NVLINK"); f.put("groupInterconnect", "PCIE"); });
        reason("R-03", "QUEUE_RESOURCE_SHORT", f -> obj(f,"availableBytes").put("memoryBytes",0));
    }

    @Test void queueUsesPriorityThenTimeThenIdAndRejectsDuplicateEntries() {
        reason("R-12", "QUEUE_NOT_HEAD", f -> f.put("requestId", 2));
        assertEquals(PASS, run("R-12", f -> { f.put("requestId",2); ((ObjectNode)f.path("queue").get(1)).put("priority",21); }).result());
        assertEquals(PASS, run("R-12", f -> ((ObjectNode)f.path("queue").get(1)).put("submittedAt", "2026-11-06T10:00:00Z")).result());
        assertEquals(ERROR, run("R-12", f -> ((ObjectNode)f.path("queue").get(1)).put("requestId", 1)).result());
    }

    @Test void jobsSamePriorityAndProtectedResidentDeploymentsCannotBePreempted() {
        reason("R-13", "QUEUE_NOT_PREEMPTIBLE", f -> f.put("holderKind","ALLOCATION"));
        reason("R-13", "QUEUE_NOT_PREEMPTIBLE", f -> f.put("taskType","DEPLOYMENT"));
        reason("R-13", "QUEUE_NOT_PREEMPTIBLE", f -> f.put("groupCode","C"));
        reason("R-13", "QUEUE_NOT_PREEMPTIBLE", f -> f.put("deploymentGpuCount",1));
        reason("R-13", "QUEUE_NOT_PREEMPTIBLE", f -> f.put("requestPriority",10));
        reason("R-13", "QUEUE_NOT_PREEMPTIBLE", f -> f.put("preemptible",false));
        assertEquals(NOT_APPLICABLE,run("R-13", f -> f.put("preemptionRequired",false)).result());
    }

    @Test void startupChecksSelectedCardsButRecoveryChecksTheWholeGroup() {
        assertEquals(PASS, run("R-14", f -> ((ObjectNode)f.path("groupCards").get(1)).put("healthy",false)).result());
        reason("R-14","QUEUE_GROUP_UNHEALTHY", f -> f.putArray("usedGpuIds").add(99));
        assertEquals(ERROR, run("R-14", f -> f.putArray("usedGpuIds").add(4).add(4)).result());
        reason("R-15","RECOVERY_UNHEALTHY", f -> ((ObjectNode)f.path("groupCards").get(1)).put("healthy",false));
        reason("R-15","RECOVERY_NO_CAPACITY", f -> obj(f,"capacityBytes").put("memoryBytes",0));
        assertEquals(ERROR, run("R-14", f -> ((ObjectNode)f.path("groupCards").get(0)).putNull("healthy")).result());
    }

    @Test void freshnessIsInclusiveAndCannotBeExtendedByCaller() {
        assertEquals(PASS, run("R-11", f -> f.put("asOf","2026-11-06T10:00:30Z")).result());
        reason("R-11","QUEUE_STALE_EVIDENCE", f -> f.put("asOf","2026-11-06T10:00:30.000000001Z"));
        reason("R-11","QUEUE_STALE_EVIDENCE", f -> f.put("asOf","2026-11-06T09:59:59Z"));
        reason("R-11","QUEUE_STALE_EVIDENCE", f -> { f.put("asOf","2026-11-06T10:01:00Z"); f.put("maxAgeSeconds",999999); ((ObjectNode)f.path("evidence").get(0)).put("validUntil","2026-11-06T11:00:00Z"); });
    }

    @Test void notificationAndStopReceiptsMustPrecedeHandoverAndStart() {
        for (String status : List.of("FAILED","TIMED_OUT","UNKNOWN","PENDING"))
            reason("R-05","NOTIFY_FAILED", f -> f.put("notificationStatus",status));
        reason("R-05","ABORT_INFLIGHT", f -> f.put("inFlightRequests",1));
        reason("R-05","ABORT_INFLIGHT", f -> f.put("stopStatus","UNKNOWN"));
        reason("R-05","ABORT_INFLIGHT", f -> f.put("gpuReleased",false));
        reason("R-05","QUEUE_CONFLICT", f -> f.put("reservationValid",false));
        reason("R-05","QUEUE_CONFLICT", f -> f.put("step","START_JOB"));
        assertEquals(ERROR, run("R-05", f -> f.put("step","RUN_SHELL")).result());
    }

    @Test void reservationRequiresCommittedReceiptAndCannotAcceptConflictingCounts() {
        reason("R-07","QUEUE_CONFLICT", f -> f.put("transactionCommitted",false));
        reason("R-07","QUEUE_CONFLICT", f -> f.put("pendingAllocationCount",2));
        reason("R-07","QUEUE_CONFLICT", f -> f.put("openHoldCount",2));
        reason("R-07","QUEUE_CONFLICT", f -> f.put("nodeAndGroupLocked",false));
        reason("R-07","DENY_DUPLICATE", f -> f.put("duplicateRequest",true));
    }

    @Test void failedPreemptionNotificationCancelsButRestoredServiceNotificationRetries() {
        var before = run("R-08", f -> f.put("deliveryStatus","TIMED_OUT"));
        assertEquals(FAIL,before.result()); assertEquals("CANCEL_AND_COMPENSATE",before.output().get("failureRoute"));
        var after = run("R-08", f -> { f.put("event","RESTORED"); f.put("deliveryStatus","FAILED"); });
        assertEquals("RETRY_NOTIFICATION", after.output().get("failureRoute"));
        assertEquals(ERROR, run("R-08", f -> f.putArray("recipients")).result());
    }

    @Test void auditRequiresRealEvidencePathsAndVersionsNotJustAnId() {
        reason("R-10","AUDIT_INCOMPLETE", f -> f.remove("snapshotId"));
        reason("R-10","AUDIT_INCOMPLETE", f -> f.putArray("evidence"));
        reason("R-10","AUDIT_INCOMPLETE", f -> ((ObjectNode)f.path("checks").get(0)).remove("ruleVersion"));
        reason("R-10","AUDIT_INCOMPLETE", f -> ((ObjectNode)f.path("checks").get(0)).remove("result"));
        reason("R-10","AUDIT_INCOMPLETE", f -> ((ObjectNode)f.path("checks").get(0)).putArray("evidenceIds").add("missing"));
        reason("R-10","AUDIT_INCOMPLETE", f -> ((ObjectNode)f.path("evidence").get(0)).put("snapshotId","another"));
    }

    @Test void closureRechecksCandidatesAndFallsBackToRestoreOrWait() {
        assertEquals("RESTORE", run("R-06", f -> {}).output().get("outcome"));
        assertEquals("WAIT_RESOURCE", run("R-06", f -> obj(obj(f,"restoreFacts"),"capacityBytes").put("memoryBytes",0)).output().get("outcome"));
        Consumer<ObjectNode> addCandidate = f -> {
            ObjectNode c = f.putObject("handoffCandidate");
            c.put("targetStillMatches",true);
            for (var entry : Map.of("queueFacts","R-12","capacityFacts","R-02","resourceFacts","R-03","healthFacts","R-14").entrySet())
                c.set(entry.getKey(), FIXTURES.get(entry.getValue()).path("definition").path("sampleFacts").deepCopy());
        };
        assertEquals("HANDOFF",run("R-06",addCandidate).output().get("outcome"));
        var insufficient = run("R-06", f -> { addCandidate.accept(f); obj(obj(obj(f,"handoffCandidate"),"capacityFacts"),"newReservationBytes").put("memoryBytes",Long.MAX_VALUE/2); });
        assertEquals("RESTORE", insufficient.output().get("outcome"));
        assertEquals(ERROR,run("R-06", f -> { addCandidate.accept(f); obj(f,"handoffCandidate").remove("healthFacts"); }).result());
        assertEquals(ERROR, run("R-06", f -> f.put("jobExitConfirmed",false)).result());
    }
}
