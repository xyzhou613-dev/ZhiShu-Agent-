package com.bemodel.compute;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration tests. Only generated m01_test_* schemas are created/dropped. */
@EnabledIfEnvironmentVariable(named = "COMPUTE_ONTOLOGY_MYSQL_URL", matches = "jdbc:mysql:.*")
class ComputeOntologyMigrationTest {
    private static final String TARGET = "4";

    @Test
    void initializesConceptsAndAttributesFromAnEmptySchema() throws Exception {
        inIsolatedSchemas(db -> {
            assertEquals(4, migrate(db.platformUrl(), TARGET).migrate().migrationsExecuted);
            verifyConcepts(db.platform());
            verifyBusinessSample(db.business());
            assertEquals(0, migrate(db.platformUrl(), TARGET).migrate().migrationsExecuted);
            assertEquals(138, count(db.platform(), "bm_attribute"));
        });
    }

    @Test
    void upgradesV3WithoutChangingIdentifiersMappingsOrSnapshots() throws Exception {
        inIsolatedSchemas(db -> {
            migrate(db.platformUrl(), "3").migrate();
            var concepts = db.platform().queryForList("SELECT id,code,status,version FROM bm_concept ORDER BY id");
            var attributes = db.platform().queryForList("SELECT id,concept_code,attr_code FROM bm_attribute ORDER BY id");
            var mappings = db.platform().queryForList("SELECT * FROM bm_mapping ORDER BY id");
            db.platform().update("INSERT INTO bm_release (version_tag,snapshot_json) VALUES (?,?)",
                    "m01-before", "{\"definition\":\"historical snapshot\"}");
            assertEquals(1, migrate(db.platformUrl(), TARGET).migrate().migrationsExecuted);
            verifyConcepts(db.platform());
            assertEquals(concepts, db.platform().queryForList("SELECT id,code,status,version FROM bm_concept ORDER BY id"));
            for (var attribute : attributes) {
                assertEquals(attribute, db.platform().queryForMap(
                        "SELECT id,concept_code,attr_code FROM bm_attribute WHERE id = ?", attribute.get("id")));
            }
            assertEquals(mappings, db.platform().queryForList("SELECT * FROM bm_mapping ORDER BY id"));
            assertEquals("{\"definition\":\"historical snapshot\"}", db.platform().queryForObject(
                    "SELECT snapshot_json FROM bm_release WHERE version_tag = 'm01-before'", String.class));
            assertEquals(0, migrate(db.platformUrl(), TARGET).migrate().migrationsExecuted);
            verifyBusinessSample(db.business());
        });
    }

    private static void verifyConcepts(JdbcTemplate jdbc) {
        var codes = jdbc.queryForList("SELECT code FROM bm_concept WHERE domain_code='COMPUTE'", String.class);
        assertEquals(18, codes.size());
        assertTrue(codes.containsAll(List.of("COMPUTE_NODE", "COMPUTE_GPU_GROUP", "COMPUTE_GPU_DEVICE",
                "COMPUTE_DEPLOYMENT", "COMPUTE_REQUEST", "COMPUTE_JOB", "COMPUTE_ALLOCATION",
                "COMPUTE_HOLD_PERIOD", "COMPUTE_RULE", "COMPUTE_RULE_CHECK", "COMPUTE_DECISION",
                "COMPUTE_ACTION", "COMPUTE_EVIDENCE", "COMPUTE_EVENT", "COMPUTE_EMPLOYEE",
                "COMPUTE_TEAM", "COMPUTE_PROJECT", "COMPUTE_TASK_TYPE")));
        for (String code : codes) {
            var concept = jdbc.queryForMap("SELECT definition,owner,status FROM bm_concept WHERE code=?", code);
            assertTrue(((String) concept.get("definition")).length() > 40, code);
            assertFalse(((String) concept.get("owner")).isBlank(), code);
            assertEquals("PUBLISHED", concept.get("status"), code);
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM bm_attribute WHERE concept_code=?", Integer.class, code) >= 3, code);
        }
        assertAttribute(jdbc, "GPU_GROUP", "IDENTIFIER", "组合唯一");
        assertAttribute(jdbc, "GPU_GROUP", "PENDING_RESERVATION", "尚未取得持有权");
        assertAttribute(jdbc, "GPU_DEVICE", "VRAM_BYTES", "单位字节");
        assertAttribute(jdbc, "NODE", "OBSERVED_AT", "UTC");
        assertAttribute(jdbc, "ALLOCATION", "STATUS", "PENDING=待生效");
        assertAttribute(jdbc, "RULE_CHECK", "RESULT", "NOT_APPLICABLE");
        assertAttribute(jdbc, "ACTION", "STATUS", "不是规则 result");
        assertAttribute(jdbc, "EMPLOYEE", "ACCOUNT", "尚未落库");
        assertAttribute(jdbc, "EVENT", "DELIVERY_STATUS", "尚未落库");
        assertAttribute(jdbc, "DEPLOYMENT", "RESTORE_CONFIG", "restore_revision_id");
        assertEquals(19, count(jdbc, "bm_mapping"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM bm_mapping m LEFT JOIN bm_attribute a
                ON a.concept_code=m.concept_code AND a.attr_code=m.attr_code WHERE a.id IS NULL
                """, Integer.class));
    }

    private static void assertAttribute(JdbcTemplate jdbc, String concept, String attr, String text) {
        assertTrue(jdbc.queryForObject("SELECT definition FROM bm_attribute WHERE concept_code=? AND attr_code=?",
                String.class, "COMPUTE_" + concept, attr).contains(text), concept + "." + attr);
    }

    private static void verifyBusinessSample(JdbcTemplate jdbc) {
        assertEquals(28, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name <> 'flyway_schema_history'
                """, Integer.class));
        assertEquals(4, count(jdbc, "gpu_group"));
        assertEquals(8, count(jdbc, "gpu_device"));
        var state = jdbc.queryForMap("""
                SELECT d.name, a.status FROM gpu_group g
                JOIN hold_period h ON h.id=g.current_hold_id
                JOIN deployment d ON d.id=h.deployment_id
                JOIN gpu_allocation a ON a.id=g.pending_allocation_id WHERE g.group_code='B'
                """);
        assertEquals("模型 B", state.get("name"));
        assertEquals("PENDING", state.get("status"));
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Flyway migrate(String url, String target) {
        return Flyway.configure().dataSource(url, username(), password())
                .locations("classpath:db/compute-platform").target(MigrationVersion.fromVersion(target)).load();
    }

    private record Databases(String platformUrl, JdbcTemplate platform, JdbcTemplate business) {}

    private static void inIsolatedSchemas(Consumer<Databases> test) throws Exception {
        String serverUrl = System.getenv("COMPUTE_ONTOLOGY_MYSQL_URL");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String platform = "m01_test_platform_" + suffix;
        String business = "m01_test_business_" + suffix;
        try (var connection = DriverManager.getConnection(serverUrl, username(), password());
             var statement = connection.createStatement()) {
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8, "Requires MySQL 8+");
            try {
                statement.executeUpdate("CREATE DATABASE `" + platform + "` CHARACTER SET utf8mb4");
                statement.executeUpdate("CREATE DATABASE `" + business + "` CHARACTER SET utf8mb4");
                String platformUrl = schemaUrl(serverUrl, platform);
                String businessUrl = schemaUrl(serverUrl, business);
                Flyway.configure().dataSource(businessUrl, username(), password())
                        .locations("classpath:db/a800-compute").load().migrate();
                test.accept(new Databases(platformUrl, jdbc(platformUrl), jdbc(businessUrl)));
            } finally {
                // Names are generated above, never taken from the user's JDBC database path.
                statement.executeUpdate("DROP DATABASE IF EXISTS `" + business + "`");
                statement.executeUpdate("DROP DATABASE IF EXISTS `" + platform + "`");
            }
        }
    }

    private static String schemaUrl(String serverUrl, String schema) throws Exception {
        var uri = URI.create(serverUrl.substring("jdbc:".length()));
        return "jdbc:" + new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/" + schema, uri.getQuery(), null);
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, username(), password()));
    }

    private static String username() {
        return System.getenv("COMPUTE_MYSQL_USERNAME");
    }

    private static String password() {
        return System.getenv("COMPUTE_MYSQL_PASSWORD");
    }
}
