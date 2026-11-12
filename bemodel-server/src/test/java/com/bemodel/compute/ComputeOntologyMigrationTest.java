package com.bemodel.compute;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration tests. Only generated m01_test_* schemas are created/dropped. */
@EnabledIfEnvironmentVariable(named = "COMPUTE_ONTOLOGY_MYSQL_URL", matches = "jdbc:mysql:.*")
class ComputeOntologyMigrationTest {
    private static final String TARGET = "5";

    @Test
    void initializesConceptsAndAttributesFromAnEmptySchema() throws Exception {
        inIsolatedSchemas(db -> {
            assertEquals(5, migrate(db.platformUrl(), TARGET).migrate().migrationsExecuted);
            verifyConcepts(db.platform());
            verifyRelations(db.platform());
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
            var relations = db.platform().queryForList("SELECT id FROM bm_relation ORDER BY id");
            var mappings = db.platform().queryForList("SELECT * FROM bm_mapping ORDER BY id");
            db.platform().update("INSERT INTO bm_release (version_tag,snapshot_json) VALUES (?,?)",
                    "m01-before", "{\"definition\":\"historical snapshot\"}");
            assertEquals(2, migrate(db.platformUrl(), TARGET).migrate().migrationsExecuted);
            verifyConcepts(db.platform());
            verifyRelations(db.platform());
            assertEquals(concepts, db.platform().queryForList("SELECT id,code,status,version FROM bm_concept ORDER BY id"));
            for (var attribute : attributes) {
                assertEquals(attribute, db.platform().queryForMap(
                        "SELECT id,concept_code,attr_code FROM bm_attribute WHERE id = ?", attribute.get("id")));
            }
            for (var relation : relations) {
                assertEquals(1, db.platform().queryForObject("SELECT COUNT(*) FROM bm_relation WHERE id=?", Integer.class, relation.get("id")));
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
        assertEquals(44, jdbc.queryForObject("""
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

    private static void verifyRelations(JdbcTemplate jdbc) {
        assertEquals(30, count(jdbc, "bm_relation"));
        for (String triple : EXPECTED_RELATIONS) {
            String[] parts = triple.split("\\|", -1);
            var relation = jdbc.queryForMap("""
                    SELECT description FROM bm_relation
                    WHERE from_concept=? AND relation_name=? AND to_concept=?
                    """, "COMPUTE_" + parts[0], parts[1], "COMPUTE_" + parts[2]);
            assertTrue(((String) relation.get("description")).startsWith("基数 "), triple);
        }
        assertTrue(jdbc.queryForObject("""
                SELECT description FROM bm_relation WHERE from_concept='COMPUTE_EVENT'
                AND relation_name='触发' AND to_concept='COMPUTE_DECISION'
                """, String.class).contains("尚未落库"));
        assertTrue(jdbc.queryForObject("""
                SELECT description FROM bm_relation WHERE from_concept='COMPUTE_HOLD_PERIOD'
                AND relation_name='持有主体' AND to_concept='COMPUTE_ALLOCATION'
                """, String.class).contains("互斥且恰好一个"));
    }

    @Test
    void preservesCustomRelationsIncludingCanonicalNameCollisions() throws Exception {
        inIsolatedSchemas(db -> {
            migrate(db.platformUrl(), "3").migrate();
            db.platform().update("""
                    INSERT INTO bm_relation (from_concept,to_concept,relation_name,description)
                    VALUES ('COMPUTE_EMPLOYEE','COMPUTE_REQUEST','提出','用户自定义标准关系'),
                           ('COMPUTE_NODE','COMPUTE_PROJECT','用户关联','保留这条额外关系')
                    """);
            db.platform().update("""
                    UPDATE bm_relation SET description='用户维护的旧关系'
                    WHERE from_concept='COMPUTE_REQUEST' AND to_concept='COMPUTE_TASK_TYPE' AND relation_name='采用'
                    """);
            var originalIds = db.platform().queryForList("SELECT id FROM bm_relation ORDER BY id", Long.class);
            migrate(db.platformUrl(), TARGET).migrate();
            assertEquals(33, count(db.platform(), "bm_relation"));
            assertEquals(originalIds, db.platform().queryForList("SELECT id FROM bm_relation WHERE id<=? ORDER BY id",
                    Long.class, originalIds.getLast()));
            assertEquals(3, db.platform().queryForObject("""
                    SELECT COUNT(*) FROM bm_relation WHERE description IN
                    ('用户自定义标准关系','保留这条额外关系','用户维护的旧关系')
                    """, Integer.class));
            for (String triple : EXPECTED_RELATIONS) {
                String[] parts = triple.split("\\|", -1);
                assertEquals(1, db.platform().queryForObject("""
                        SELECT COUNT(*) FROM bm_relation WHERE from_concept=? AND relation_name=? AND to_concept=?
                        """, Integer.class, "COMPUTE_" + parts[0], parts[1], "COMPUTE_" + parts[2]));
            }
            assertEquals(0, migrate(db.platformUrl(), TARGET).migrate().migrationsExecuted);
        });
    }

    // Expected edges follow v0.7 section 4, not the migration SQL.
    private static final List<String> EXPECTED_RELATIONS = List.of(
            "NODE|包含|GPU_GROUP", "GPU_GROUP|包含|GPU_DEVICE", "GPU_GROUP|承载|DEPLOYMENT",
            "DEPLOYMENT|归属|PROJECT", "DEPLOYMENT|负责|TEAM", "EMPLOYEE|属于|TEAM",
            "TEAM|参与|PROJECT", "EMPLOYEE|提出|REQUEST", "PROJECT|归属|REQUEST",
            "REQUEST|指定（期望）|TASK_TYPE", "REQUEST|产生|JOB", "JOB|实例化（实际）|TASK_TYPE",
            "JOB|占用|ALLOCATION", "ALLOCATION|绑定|GPU_GROUP", "ALLOCATION|使用|GPU_DEVICE",
            "HOLD_PERIOD|持有|GPU_GROUP", "HOLD_PERIOD|持有主体|DEPLOYMENT", "HOLD_PERIOD|持有主体|ALLOCATION",
            "REQUEST|触发|DECISION", "DECISION|前序|DECISION", "EVENT|触发|DECISION",
            "DECISION|替代|DECISION", "DECISION|包含|RULE_CHECK", "RULE_CHECK|引用|RULE",
            "RULE_CHECK|依据|EVIDENCE", "RULE_CHECK|依赖|RULE_CHECK", "DECISION|产生|ACTION",
            "DECISION|产生|EVENT", "EVENT|通知|EMPLOYEE", "EVENT|通知|TEAM");

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Flyway migrate(String url, String target) {
        return Flyway.configure().dataSource(url, username(), password())
                .locations("classpath:db/compute-platform").target(MigrationVersion.fromVersion(target)).load();
    }

    private static void inIsolatedSchemas(Consumer<ComputeMysqlTestDatabase> test) throws Exception {
        try (var db = ComputeMysqlTestDatabase.create()) {
            test.accept(db);
        }
    }

    private static String username() {
        return ComputeMysqlTestDatabase.username();
    }

    private static String password() {
        return ComputeMysqlTestDatabase.password();
    }
}
