package com.bemodel.compute;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static com.bemodel.compute.ComputeRuleEvaluator.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class ComputeEvidenceAdmissionTest {
    private final com.fasterxml.jackson.databind.ObjectMapper json = ComputeRuleEvaluatorTest.JSON;

    private ComputeEvidenceService.Detail fixture(boolean usable) {
        var ledger = json.createObjectNode();
        var request = ledger.putObject("request").put("requested_gpu_count", 1).put("type_code", "TRAINING");
        request.putObject("requested_resource_spec").put("vramBytesPerGpu", 80).put("memoryBytes", 32);
        var manifest = json.createObjectNode().put("usable", usable);
        manifest.putArray("issues").add("GPU:1:OBSERVATION_ERROR");
        Instant time = Instant.parse("2026-11-06T10:00:10Z");
        return new ComputeEvidenceService.Detail(new ComputeEvidenceService.Snapshot(7, time, 4, 1, 2, "admin", true, manifest),
                List.of(new ComputeEvidenceService.Evidence(11, "PLATFORM_LEDGER", "request:2", null, time, time, ledger),
                        new ComputeEvidenceService.Evidence(12, "FIXTURE", "observation:1", 1L, time, time.plusSeconds(30), json.createObjectNode())), false);
    }

    @Test void derivesFactsAndLinksOnlyFromTheCapturedServerRecords() {
        var evidence = fixture(true);
        ObjectNode facts = ComputeEvidenceAdmission.facts(evidence, json);
        assertEquals("TRAIN", facts.path("R-01").path("taskType").asText());
        assertEquals(0, facts.path("R-01").path("resourceSpec").path("temporaryBytes").asInt(-1));
        assertEquals(evidence.snapshot().asOf().toString(), facts.path("R-11").path("asOf").asText());
        assertEquals(1, facts.path("R-11").path("evidence").size());
        assertEquals(List.of(11L), ComputeEvidenceAdmission.references(evidence, "R-01"));
        assertEquals(List.of(12L), ComputeEvidenceAdmission.references(evidence, "R-11"));
    }

    @Test void freshTimestampsCannotOverrideFailedOrMissingEvidence() {
        var checks = List.of(new ComputeRuleRunner.Check(1, "R-01", 1, 2, PASS, "PASS", Map.of()),
                new ComputeRuleRunner.Check(2, "R-11", 1, 3, PASS, "PASS", Map.of()));
        var evaluation = new ComputeRuleRunner.Evaluation(null, "ADMISSION", PASS, checks);
        var guarded = ComputeEvidenceAdmission.guard(evaluation, fixture(false));
        assertEquals(FAIL, guarded.overallResult());
        assertEquals("QUEUE_STALE_EVIDENCE", guarded.checks().getLast().reasonCode());
        assertEquals(7L, guarded.checks().getLast().output().get("snapshotId"));
        assertSame(evaluation, ComputeEvidenceAdmission.guard(evaluation, fixture(true)));
    }
}
