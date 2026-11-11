package com.bemodel.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import static com.bemodel.compute.ComputeRuleEvaluator.Status;

/** Server projection for ADMISSION only; the other stages still require caller facts. */
final class ComputeEvidenceAdmission {
    private ComputeEvidenceAdmission() {}

    static ObjectNode facts(ComputeEvidenceService.Detail evidence, ObjectMapper json) {
        var facts = json.createObjectNode();
        var request = evidence.evidence().stream().filter(e -> "PLATFORM_LEDGER".equals(e.source()))
                .findFirst().orElseThrow().values().path("request");
        var specification = json.createObjectNode();
        for (String key : List.of("vramBytesPerGpu", "memoryBytes", "temporaryBytes", "persistentBytes")) {
            var value = request.path("requested_resource_spec").path(key);
            if (!value.isMissingNode()) specification.set(key, value);
        }
        // N-01 permits omitted optional storage requirements; zero here is a request default, never a sensor reading.
        if (!specification.has("temporaryBytes")) specification.put("temporaryBytes", 0);
        if (!specification.has("persistentBytes")) specification.put("persistentBytes", 0);
        var spec = facts.putObject("R-01");
        spec.set("gpuCount", request.path("requested_gpu_count"));
        String task = request.path("type_code").asText();
        spec.put("taskType", "TRAINING".equals(task) ? "TRAIN" : task);
        spec.set("resourceSpec", specification);
        var freshness = facts.putObject("R-11").put("asOf", evidence.snapshot().asOf().toString());
        var observations = freshness.putArray("evidence");
        for (var e : evidence.evidence()) if (e.observationId() != null) observations.addObject()
                .put("observedAt", e.observedAt().toString()).put("validUntil", e.validUntil().toString());
        return facts;
    }

    static ComputeRuleRunner.Evaluation guard(ComputeRuleRunner.Evaluation evaluation, ComputeEvidenceService.Detail evidence) {
        if (evidence.snapshot().manifest().path("usable").asBoolean()) return evaluation;
        // A fresh timestamp cannot make missing, failed, mistyped or non-authoritative observations usable.
        var checks = evaluation.checks().stream().map(check -> "R-11".equals(check.ruleCode())
                ? new ComputeRuleRunner.Check(check.ruleVersionId(), check.ruleCode(), check.ruleVersion(), check.definitionVersion(),
                    Status.FAIL, "QUEUE_STALE_EVIDENCE", Map.of("snapshotId", evidence.snapshot().id(),
                    "asOf", evidence.snapshot().asOf().toString(), "issues", evidence.snapshot().manifest().path("issues")))
                : check).toList();
        return new ComputeRuleRunner.Evaluation(evaluation.snapshot(), evaluation.stage(), ComputeRuleRunner.summarize(checks), checks);
    }

    static List<Long> references(ComputeEvidenceService.Detail evidence, String code) {
        return evidence.evidence().stream().filter(e -> "R-01".equals(code)
                ? e.observationId() == null && "PLATFORM_LEDGER".equals(e.source()) : e.observationId() != null)
                .map(ComputeEvidenceService.Evidence::id).toList();
    }
}
