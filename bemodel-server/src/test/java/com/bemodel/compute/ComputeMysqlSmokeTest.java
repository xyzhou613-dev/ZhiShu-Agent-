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

    @Test
    void migratesBothSchemasAndServesMappedSample() {
        JsonNode login = http.postForObject("/api/auth/login", Map.of(
                "username", "admin", "password", ADMIN_PASSWORD), JsonNode.class);
        assertEquals(0, login.path("code").asInt(-1));
        var headers = new HttpHeaders();
        headers.setBearerAuth(login.path("data").path("token").asText());

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
