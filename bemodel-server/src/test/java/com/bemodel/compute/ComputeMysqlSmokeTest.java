package com.bemodel.compute;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.datasource.service.SchemaScanService;
import com.bemodel.auth.JwtService;
import com.bemodel.auth.PlatformUser;
import java.net.URI;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "COMPUTE_MYSQL_SMOKE", matches = "true")
@EnabledIfEnvironmentVariable(named = "COMPUTE_ONTOLOGY_MYSQL_URL", matches = "jdbc:mysql:.*")
@ActiveProfiles("compute")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComputeMysqlSmokeTest {
    private static final String ADMIN_PASSWORD = "m01-isolated-test-admin";
    private static ComputeMysqlTestDatabase databases;

    // Replace fixed-name business bootstrap only in this test; real queries use the isolated business database.
    @MockBean
    private ComputeDatabase.Store store;
    @MockBean
    private ComputeDatasourceBootstrap bootstrap;
    @Autowired
    private DatasourceService datasources;
    @Autowired
    private SchemaScanService scanner;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) throws Exception {
        databases = ComputeMysqlTestDatabase.create();
        properties.add("spring.datasource.url", databases::platformUrl);
        properties.add("spring.datasource.username", ComputeMysqlTestDatabase::username);
        properties.add("spring.datasource.password", ComputeMysqlTestDatabase::password);
        properties.add("spring.flyway.locations", () -> "classpath:db/compute-platform");
        properties.add("spring.config.import", () -> "");
        properties.add("COMPUTE_ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
        properties.add("jwt.secret", () -> "m01-isolated-test-jwt-secret-at-least-32-bytes");
        properties.add("app.secret-key", () -> "m01-isolated-test-encryption-key");
        properties.add("bemodel.inspect.cron", () -> "-");
        properties.add("bemodel.reconcile.cron", () -> "-");
        properties.add("bemodel.proposal.cron", () -> "-");
    }

    @BeforeEach
    void useIsolatedBusinessDatabase() {
        var uri = URI.create(databases.businessUrl().substring("jdbc:".length()));
        var endpoint = new ComputeDatabase.Endpoint(uri.getHost(), uri.getPort(), databases.businessUrl());
        when(store.jdbc()).thenReturn(databases.business());
        when(store.endpoint()).thenReturn(endpoint);
        if (datasources.getByCode("A800_COMPUTE") == null) {
            var source = new Datasource();
            source.setDsCode("A800_COMPUTE");
            source.setDsName("隔离算力测试库");
            source.setProductName("算力管理");
            source.setDbType("MYSQL");
            source.setHost(uri.getHost());
            source.setPort(uri.getPort());
            source.setDbName(databases.businessName());
            source.setUsername(ComputeMysqlTestDatabase.username());
            source.setPassword(ComputeMysqlTestDatabase.password());
            datasources.create(source);
            scanner.scan("A800_COMPUTE");
        }
    }

    @AfterAll
    static void removeIsolatedDatabases() throws Exception {
        if (databases != null) databases.close();
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JwtService jwt;

    @Test
    void migratesBothSchemasAndServesMappedSample() {
        var headers = adminHeaders();

        JsonNode concepts = get("/api/concept/list?domainCode=COMPUTE", headers).path("data");
        assertEquals(18, concepts.size());
        for (JsonNode concept : concepts) {
            JsonNode detail = get("/api/concept/detail/" + concept.path("code").asText(), headers).path("data");
            assertTrue(detail.path("attributes").size() >= 3);
            assertFalse(detail.path("concept").path("owner").asText().isBlank());
        }
        JsonNode allocation = get("/api/concept/detail/COMPUTE_ALLOCATION", headers).path("data");
        assertTrue(allocation.path("concept").path("definition").asText().contains("待生效预留"));
        assertTrue(allocation.path("attributes").toString().contains("PENDING=待生效"));
        JsonNode event = get("/api/concept/detail/COMPUTE_EVENT", headers).path("data");
        assertTrue(event.path("relations").toString().contains("尚未落库"));
        JsonNode project = get("/api/concept/detail/COMPUTE_PROJECT", headers).path("data");
        boolean projectOwnsRequests = false;
        for (JsonNode relation : project.path("relations")) {
            projectOwnsRequests |= "COMPUTE_PROJECT".equals(relation.path("fromConcept").asText())
                    && "COMPUTE_REQUEST".equals(relation.path("toConcept").asText())
                    && "归属".equals(relation.path("relationName").asText());
        }
        assertTrue(projectOwnsRequests);
        JsonNode source = get("/api/datasource/tables/A800_COMPUTE", headers).path("data");
        assertTrue(source.isArray() && source.size() >= 28);

        JsonNode payload = get("/api/compute/groups?evidenceAsOf=2026-11-06T10:00:00Z", headers);
        assertEquals(0, payload.path("code").asInt(-1));
        JsonNode groups = payload.path("data").path("groups");
        assertEquals(4, groups.size());
        int cardCount = 0;
        for (JsonNode group : groups) {
            cardCount += group.path("cards").size();
        }
        assertEquals(8, cardCount);

        JsonNode groupB = find(groups, "B");
        assertEquals("DEPLOYMENT", groupB.path("holder").path("kind").asText());
        assertEquals(2, groupB.path("holder").path("id").asInt());
        assertEquals(1, groupB.path("reservation").path("requestId").asInt());
        assertFalse(groupB.path("directlyAllocatable").asBoolean(true));
        assertTrue(find(groups, "FLEX").path("directlyAllocatable").asBoolean());

        JsonNode expired = get("/api/compute/groups?evidenceAsOf=2026-11-06T10:00:31Z", headers);
        assertFalse(find(expired.path("data").path("groups"), "FLEX").path("directlyAllocatable").asBoolean(true));
    }

    @Test
    void submitsListsAndCancelsUsingExistingData() {
        var headers = adminHeaders();
        String key = UUID.randomUUID().toString();
        var body = requestBody(key);
        body.put("requestedGpuCount", 5);
        JsonNode created = ok(post("/api/compute/requests", body, headers));
        long id = created.path("id").asLong();
        assertTrue(id > 1);
        assertEquals("SUBMITTED", created.path("status").asText());
        assertEquals(5, created.path("requestedGpuCount").asInt());
        assertEquals(34359738368L, created.path("requestedResourceSpec").path("memoryBytes").asLong());
        JsonNode detail = ok(get("/api/compute/requests/" + id, headers));
        assertEquals(created, detail.path("request"));
        assertTrue(detail.path("jobs").isEmpty());
        assertTrue(detail.path("currentJob").isNull());

        JsonNode page = ok(get("/api/compute/requests?pageNum=1&pageSize=1", headers));
        assertEquals(1, page.path("list").size());
        assertEquals(id, page.path("list").get(0).path("id").asLong());
        assertTrue(page.path("total").asLong() >= 2);

        String cancelPath = "/api/compute/requests/" + id + "/cancel";
        JsonNode cancelled = ok(post(cancelPath, null, headers));
        assertEquals("CANCELLED", cancelled.path("status").asText());
        assertEquals(cancelled, ok(post(cancelPath, null, headers)));
        assertEquals(cancelled, ok(post("/api/compute/requests", body, headers)));
    }

    @Test
    void validatesReferencesAndProtectsApprovedAndQueuedRequests() {
        var headers = adminHeaders();
        JsonNode sample = ok(get("/api/compute/requests/1", headers));
        assertEquals("APPROVED", sample.path("request").path("status").asText());
        assertEquals(1, sample.path("currentJob").path("id").asLong());
        assertEquals("仅可取消尚未进入调度的已提交申请",
                error(post("/api/compute/requests/1/cancel", null, headers)));
        assertEquals("算力申请不存在", error(get("/api/compute/requests/9223372036854775807", headers)));

        var body = requestBody(UUID.randomUUID().toString());
        for (String field : List.of("employeeId", "projectId", "taskTypeId")) {
            var invalid = new HashMap<>(body);
            invalid.put(field, Long.MAX_VALUE);
            assertTrue(error(post("/api/compute/requests", invalid, headers)).endsWith("不存在"));
        }
        var invalid = new HashMap<>(body);
        invalid.put("requestedGpuCount", 32768);
        assertTrue(error(post("/api/compute/requests", invalid, headers)).contains("32767"));
        invalid = new HashMap<>(body);
        invalid.put("requestedResourceSpec", Map.of("memoryBytes", -1));
        assertTrue(error(post("/api/compute/requests", invalid, headers)).contains("memoryBytes"));
        invalid = new HashMap<>(body);
        invalid.put("launchSpec", Map.of("commandArgs", List.of("run")));
        assertTrue(error(post("/api/compute/requests", invalid, headers)).contains("imageRef"));
        assertEquals(0, databases.business().queryForObject(
                "SELECT COUNT(*) FROM compute_request WHERE client_request_key = ?", Integer.class,
                body.get("clientRequestKey")));

        long id = ok(post("/api/compute/requests", body, headers)).path("id").asLong();
        databases.business().update("""
                INSERT INTO compute_queue (request_id, priority, submitted_at, reason_code)
                VALUES (?, 2, UTC_TIMESTAMP(6), 'TEST_WAIT')
                """, id);
        assertEquals("仅可取消尚未进入调度的已提交申请",
                error(post("/api/compute/requests/" + id + "/cancel", null, headers)));
        assertEquals("SUBMITTED", ok(get("/api/compute/requests/" + id, headers))
                .path("request").path("status").asText());
    }

    @Test
    void replaysLegacyAndConcurrentSubmissionsByContent() throws Exception {
        var headers = adminHeaders();
        var sampleBody = requestBody("a800-demo-training");
        sampleBody.put("requestedGpuCount", 2);
        assertEquals(1, ok(post("/api/compute/requests", sampleBody, headers)).path("id").asLong());
        sampleBody.put("requestedGpuCount", 1);
        assertEquals("申请编号已用于不同的申请内容",
                error(post("/api/compute/requests", sampleBody, headers)));

        String key = UUID.randomUUID().toString();
        var body = requestBody(key);
        body.put("launchSpec", Map.of("imageRef", "demo/train", "commandArgs", List.of("train", "--demo")));
        List<JsonNode> responses = concurrently(() -> ok(post("/api/compute/requests", body, headers)));
        assertEquals(responses.get(0), responses.get(1));
        assertEquals(1, databases.business().queryForObject(
                "SELECT COUNT(*) FROM compute_request WHERE employee_id = 1 AND client_request_key = ?",
                Integer.class, key));
        var reordered = new java.util.LinkedHashMap<String, Object>();
        reordered.put("commandArgs", List.of("train", "--demo"));
        reordered.put("imageRef", "demo/train");
        body.put("launchSpec", reordered);
        assertEquals(responses.get(0), ok(post("/api/compute/requests", body, headers)));
        body.put("requestedGpuCount", 2);
        assertEquals("申请编号已用于不同的申请内容", error(post("/api/compute/requests", body, headers)));
    }

    @Test
    void requiresAnAdministratorOrEditorForRequestReadsAndWrites() {
        assertEquals(401, http.getForEntity("/api/compute/requests/1", JsonNode.class).getStatusCode().value());
        var viewer = headersForRole("VIEWER");
        assertEquals(403, http.exchange("/api/compute/requests/1", HttpMethod.GET,
                new HttpEntity<>(viewer), JsonNode.class).getStatusCode().value());
        assertEquals(403, http.exchange("/api/compute/requests", HttpMethod.POST,
                new HttpEntity<>(requestBody(UUID.randomUUID().toString()), viewer), JsonNode.class)
                .getStatusCode().value());
        assertEquals(1, ok(get("/api/compute/requests/1", headersForRole("EDITOR")))
                .path("request").path("id").asLong());
    }

    @Test
    void retriesAFailedJobOnceUnderConcurrentRequests() throws Exception {
        var headers = adminHeaders();
        FailedJob failed = failedJob(headers);
        var responses = concurrently(() -> ok(post(failed.retryPath(), null, headers)));
        JsonNode replacement = responses.getFirst();
        long replacementId = replacement.path("id").asLong();
        assertTrue(replacementId > failed.id());
        assertEquals(responses.getLast(), replacement);
        assertEquals(replacement, ok(post(failed.retryPath(), null, headers)));
        assertEquals("PENDING", replacement.path("status").asText());
        assertEquals(failed.requestId(), replacement.path("requestId").asLong());
        assertEquals(1, replacement.path("taskTypeId").asLong());
        assertEquals(2, replacement.path("effectiveGpuCount").asInt());
        assertEquals(12345678, replacement.path("effectiveResourceSpec").path("memoryBytes").asLong());
        assertTrue(replacement.path("startedAt").isNull());
        assertTrue(replacement.path("endedAt").isNull());

        JsonNode detail = ok(get("/api/compute/requests/" + failed.requestId(), headers));
        assertEquals("APPROVED", detail.path("request").path("status").asText());
        assertEquals(replacement, detail.path("currentJob"));
        assertEquals(2, detail.path("jobs").size());
        assertEquals(failed.id(), detail.path("jobs").get(1).path("id").asLong());
        assertEquals("FAILED", detail.path("jobs").get(1).path("status").asText());
        assertEquals(0, databases.business().queryForObject(
                "SELECT COUNT(*) FROM gpu_allocation WHERE job_id = ?", Integer.class, replacementId));
    }

    @Test
    void rejectsRetryWhenTheRequestOrSourceJobIsIneligible() {
        var headers = adminHeaders();
        assertEquals("仅可重试已结束的失败作业",
                error(post("/api/compute/requests/1/jobs/1/retry", null, headers)));
        FailedJob failed = failedJob(headers);
        assertEquals("申请下不存在该作业",
                error(post("/api/compute/requests/1/jobs/" + failed.id() + "/retry", null, headers)));

        var jdbc = databases.business();
        jdbc.update("UPDATE compute_request SET status = 'SUBMITTED' WHERE id = ?", failed.requestId());
        assertEquals("仅已批准的申请可重试作业", error(post(failed.retryPath(), null, headers)));
        jdbc.update("UPDATE compute_request SET status = 'APPROVED' WHERE id = ?", failed.requestId());
        jdbc.update("UPDATE compute_job SET ended_at = NULL WHERE id = ?", failed.id());
        assertEquals("仅可重试已结束的失败作业", error(post(failed.retryPath(), null, headers)));
        jdbc.update("UPDATE compute_job SET ended_at = UTC_TIMESTAMP(6) WHERE id = ?", failed.id());

        String blocked = "申请仍有关联的活跃作业、执行流程或有效分配";
        jdbc.update("INSERT INTO active_job (request_id, job_id) VALUES (?, ?)", failed.requestId(), failed.id());
        assertEquals(blocked, error(post(failed.retryPath(), null, headers)));
        jdbc.update("DELETE FROM active_job WHERE request_id = ?", failed.requestId());

        jdbc.update("INSERT INTO gpu_allocation (job_id, group_id, memory_bytes) VALUES (?, 4, 0)", failed.id());
        assertEquals(blocked, error(post(failed.retryPath(), null, headers)));
        jdbc.update("UPDATE gpu_allocation SET status = 'OCCUPIED' WHERE job_id = ?", failed.id());
        assertEquals(blocked, error(post(failed.retryPath(), null, headers)));
        jdbc.update("UPDATE gpu_allocation SET status = 'RELEASED' WHERE job_id = ?", failed.id());

        jdbc.update("INSERT INTO evidence_snapshot (as_of) VALUES (UTC_TIMESTAMP(6))");
        jdbc.update("""
                INSERT INTO decision (request_id, kind, outcome, as_of, snapshot_id)
                SELECT ?, 'SUBMISSION', 'APPROVED', as_of, id FROM evidence_snapshot ORDER BY id DESC LIMIT 1
                """, failed.requestId());
        jdbc.update("""
                INSERT INTO workflow_run (request_id, decision_id, group_id, job_id)
                SELECT request_id, id, 4, ? FROM decision WHERE request_id = ?
                """, failed.id(), failed.requestId());
        assertEquals(blocked, error(post(failed.retryPath(), null, headers)));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM compute_job WHERE request_id = ?", Integer.class, failed.requestId()));
    }

    private FailedJob failedJob(HttpHeaders headers) {
        long requestId = ok(post("/api/compute/requests", requestBody(UUID.randomUUID().toString()), headers))
                .path("id").asLong();
        var jdbc = databases.business();
        jdbc.update("UPDATE compute_request SET status = 'APPROVED' WHERE id = ?", requestId);
        jdbc.update("""
                INSERT INTO compute_job (request_id, task_type_id, effective_gpu_count, effective_resource_spec,
                                         status, started_at, ended_at)
                VALUES (?, 1, 2, JSON_OBJECT('memoryBytes', 12345678), 'FAILED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, requestId);
        long jobId = jdbc.queryForObject("SELECT id FROM compute_job WHERE request_id = ?", Long.class, requestId);
        return new FailedJob(requestId, jobId);
    }

    private record FailedJob(long requestId, long id) {
        String retryPath() {
            return "/api/compute/requests/" + requestId + "/jobs/" + id + "/retry";
        }
    }

    private static Map<String, Object> requestBody(String key) {
        return new HashMap<>(Map.of("employeeId", 1, "projectId", 1, "taskTypeId", 1,
                "clientRequestKey", key, "requestedGpuCount", 1,
                "requestedResourceSpec", Map.of("memoryBytes", 34359738368L),
                "launchSpec", Map.of("imageRef", "demo/train")));
    }

    private HttpHeaders adminHeaders() {
        JsonNode login = http.postForObject("/api/auth/login", Map.of(
                "username", "admin", "password", ADMIN_PASSWORD), JsonNode.class);
        var headers = new HttpHeaders();
        headers.setBearerAuth(ok(login).path("token").asText());
        return headers;
    }

    private HttpHeaders headersForRole(String role) {
        var user = new PlatformUser();
        user.setUsername("test-" + role);
        user.setRole(role);
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.issue(user));
        return headers;
    }

    private JsonNode post(String path, Object body, HttpHeaders headers) {
        var result = http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        assertTrue(result.getStatusCode().is2xxSuccessful(), path);
        return result.getBody();
    }

    private static JsonNode ok(JsonNode result) {
        assertEquals(0, result.path("code").asInt(-1), result.toString());
        return result.path("data");
    }

    private static String error(JsonNode result) {
        assertEquals(500, result.path("code").asInt(-1), result.toString());
        return result.path("msg").asText();
    }

    private static List<JsonNode> concurrently(Supplier<JsonNode> request) throws Exception {
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<JsonNode> call = () -> {
                ready.countDown();
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                return request.get();
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    private JsonNode get(String path, HttpHeaders headers) {
        var result = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertTrue(result.getStatusCode().is2xxSuccessful(), path);
        return result.getBody();
    }

    private static JsonNode find(JsonNode groups, String code) {
        for (JsonNode group : groups) {
            if (code.equals(group.path("code").asText())) {
                return group;
            }
        }
        throw new AssertionError("Missing group " + code);
    }
}
