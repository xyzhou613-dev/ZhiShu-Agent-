package com.bemodel.compute;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
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
@ActiveProfiles("compute")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ComputeMysqlSmokeTest {
    @Autowired
    private TestRestTemplate http;

    @Test
    void migratesBothSchemasAndServesMappedSample() {
        JsonNode login = http.postForObject("/api/auth/login", Map.of(
                "username", "admin", "password", System.getenv("COMPUTE_ADMIN_PASSWORD")), JsonNode.class);
        assertEquals(0, login.path("code").asInt(-1));
        var headers = new HttpHeaders();
        headers.setBearerAuth(login.path("data").path("token").asText());

        assertEquals(0, get("/api/concept/detail/COMPUTE_GPU_DEVICE", headers).path("code").asInt(-1));
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
