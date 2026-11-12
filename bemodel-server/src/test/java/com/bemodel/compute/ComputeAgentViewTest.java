package com.bemodel.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComputeAgentViewTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final ComputeAgentView view = new ComputeAgentView(JSON);

    @Test
    void requestProjectionDropsLaunchSpecIdempotencyKeyAndRequesterIdentity() {
        JsonNode resources = JSON.createObjectNode().put("memoryBytes", 1073741824L)
                .put("vramBytesPerGpu", 85899345920L).put("gpuInterconnect", "NONE");
        var launch = JSON.createObjectNode().put("imageRef", "secret/image");
        launch.putArray("commandArgs").add("train").add("--token=secret");
        launch.putArray("mounts").addObject().put("path", "/secret");
        var request = new ComputeRequestService.Request(7, 42, 9, 3, "idem-key", 2,
                resources, launch, "APPROVED", Instant.parse("2026-11-12T00:00:00Z"));
        var job = new ComputeRequestService.Job(11, 7, 3, 2, resources, "RUNNING",
                Instant.parse("2026-11-12T00:01:00Z"), Instant.parse("2026-11-12T00:02:00Z"), null);
        JsonNode out = view.requestDetail(new ComputeRequestService.Detail(request, List.of(job), job));

        assertEquals(7, out.path("request").path("id").asLong());
        assertEquals(2, out.path("request").path("requestedGpuCount").asInt());
        assertEquals(1073741824L, out.path("request").path("requestedResourceSpec").path("memoryBytes").asLong());
        assertTrue(out.path("request").path("employeeId").isMissingNode());
        assertTrue(out.path("request").path("clientRequestKey").isMissingNode());
        assertTrue(out.path("request").path("launchSpec").isMissingNode());
        assertEquals(1, out.path("jobs").size());
        assertEquals("RUNNING", out.path("jobs").get(0).path("status").asText());
        assertEquals(11, out.path("currentJob").path("id").asLong());
        assertFalse(out.path("authorizesExecution").asBoolean(true));
        assertFalse(out.toString().contains("secret"));
    }

    @Test
    void groupProjectionKeepsResourceFactsAndDropsRequesterAndRestoreOwnerIdentity() {
        var observation = new ComputeGroupQuery.Observation("A800_DEMO_FIXTURE",
                Instant.parse("2026-11-12T00:00:00Z"), Instant.parse("2026-11-12T00:00:30Z"),
                "OK", true, false);
        var group = new ComputeGroupQuery.Group("B",
                new ComputeGroupQuery.Holder("DEPLOYMENT", 2, "model-b"),
                new ComputeGroupQuery.Reservation(21, 5, 7),
                List.of(new ComputeGroupQuery.Gpu("GPU-0", 0, 85899345920L, observation)),
                false, List.of("GROUP_HELD", "GROUP_RESERVED"), 2, true, true, 30,
                new ComputeGroupQuery.Usage("DEPLOYMENT_GROUP_BASELINE", List.of("GPU-0"), List.of()),
                new ComputeGroupQuery.Usage("PENDING_ALLOCATION_GPU", List.of("GPU-0"), List.of()),
                new ComputeGroupQuery.RequestRef(1, 1, 42, 9),
                new ComputeGroupQuery.RequestRef(7, 11, 43, 9),
                new ComputeGroupQuery.RestoreResponsibility(2, 22, 3, 42, 100L, "WAITING"));

        JsonNode out = view.project(ComputeFieldCatalog.GROUP, group);

        assertEquals("B", out.path("code").asText());
        assertEquals("DEPLOYMENT", out.path("holder").path("kind").asText());
        assertEquals("model-b", out.path("holder").path("name").asText());
        assertEquals(1, out.path("cards").size());
        assertEquals("GPU-0", out.path("cards").get(0).path("uuid").asText());
        assertTrue(out.path("cards").get(0).path("observation").path("healthy").asBoolean());
        assertEquals(2, out.path("reasons").size());
        assertTrue(out.path("holderRequest").path("employeeId").isMissingNode());
        assertTrue(out.path("pendingRequest").path("employeeId").isMissingNode());
        assertTrue(out.path("restoreResponsibility").path("ownerEmployeeId").isMissingNode());
        assertEquals("WAITING", out.path("restoreResponsibility").path("workflowStatus").asText());
    }

    @Test
    void actionProjectionKeepsStatusAndDropsReceiptAndIdempotencyKey() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 5L);
        row.put("workflow_run_id", 100L);
        row.put("decision_id", 9L);
        row.put("action_type", "START");
        row.put("target_kind", "JOB");
        row.put("target_id", 11L);
        row.put("idempotency_key", "secret-operation-key");
        row.put("status", "SUCCEEDED");
        row.put("attempt_count", 1);
        row.put("started_at", "2026-11-12 00:00:00");
        row.put("finished_at", "2026-11-12 00:00:01");
        row.put("receipt", "{\"host\":\"secret-host\",\"command\":\"rm -rf /\"}");
        row.put("error_code", "NONE");
        row.put("raw_payload", "secret");

        List<JsonNode> out = view.actionResults(List.of(row));

        assertEquals(1, out.size());
        assertEquals("SUCCEEDED", out.get(0).path("status").asText());
        assertEquals("START", out.get(0).path("action_type").asText());
        assertEquals("NONE", out.get(0).path("error_code").asText());
        assertTrue(out.get(0).path("receipt").isMissingNode());
        assertTrue(out.get(0).path("idempotency_key").isMissingNode());
        assertTrue(out.get(0).path("raw_payload").isMissingNode());
        assertFalse(out.get(0).toString().contains("secret"));
    }

    @Test
    void catalogNeverMarksSensitiveFieldsVisibleAndCoversAllProjectedResources() {
        for (ComputeFieldPolicy policy : ComputeFieldCatalog.all()) {
            assertFalse(policy.path().isBlank(), policy.resource());
            if (policy.sensitivity() == ComputeSensitivity.SENSITIVE) {
                assertFalse(policy.agentVisible(), policy.resource() + "." + policy.path());
            }
            if (policy.agentVisible()) {
                assertNotEquals(ComputeSensitivity.SENSITIVE, policy.sensitivity());
            }
        }
        assertTrue(ComputeFieldCatalog.byResource().keySet().containsAll(
                List.of(ComputeFieldCatalog.REQUEST, ComputeFieldCatalog.JOB,
                        ComputeFieldCatalog.GROUP, ComputeFieldCatalog.ACTION_RESULT)));
    }
}
