package com.bemodel.compute;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M-17 重启保护：重复启动只执行未应用过的版本化迁移，不覆盖已有管理员、演示数据和业务记录。
 *
 * <p>只使用随机生成的 m01_test_* 隔离库，结束后删除。</p>
 */
@EnabledIfEnvironmentVariable(named = "COMPUTE_ONTOLOGY_MYSQL_URL", matches = "jdbc:mysql:.*")
class ComputeRestartProtectionTest {
    @Test
    void restartKeepsAdminPasswordDemoRowsAndBusinessRecords() throws Exception {
        try (var db = ComputeMysqlTestDatabase.create()) {
            Flyway.configure().dataSource(db.platformUrl(), ComputeMysqlTestDatabase.username(),
                    ComputeMysqlTestDatabase.password()).locations("classpath:db/compute-platform").load().migrate();
            var platform = db.platform();
            var business = db.business();
            var encoder = new BCryptPasswordEncoder();

            new ComputeAdminBootstrap(platform, encoder, "restart-admin-password-1").run(null);
            String initialHash = platform.queryForObject(
                    "SELECT password_hash FROM bm_user WHERE username = 'admin'", String.class);
            assertNotNull(initialHash);
            assertEquals(1, platform.queryForObject("SELECT COUNT(*) FROM bm_user", Integer.class));

            // A second start with a different configured password must not rotate the existing administrator.
            new ComputeAdminBootstrap(platform, encoder, "restart-admin-password-2").run(null);
            assertEquals(1, platform.queryForObject("SELECT COUNT(*) FROM bm_user", Integer.class));
            assertEquals(initialHash, platform.queryForObject(
                    "SELECT password_hash FROM bm_user WHERE username = 'admin'", String.class));

            int groups = business.queryForObject("SELECT COUNT(*) FROM gpu_group", Integer.class);
            int devices = business.queryForObject("SELECT COUNT(*) FROM gpu_device", Integer.class);
            assertEquals(4, groups);
            assertEquals(8, devices);

            business.update("""
                    INSERT INTO compute_request (employee_id, project_id, task_type_id, client_request_key,
                        request_fingerprint, requested_gpu_count, requested_resource_spec, launch_spec)
                    VALUES (1, 1, 1, 'm17-restart', ?, 1, '{"memoryBytes": 1024}', '{"imageRef": "demo/train"}')
                    """, "a".repeat(64));
            long requestId = business.queryForObject(
                    "SELECT id FROM compute_request WHERE client_request_key = 'm17-restart'", Long.class);
            LocalDateTime submittedAt = business.queryForObject(
                    "SELECT submitted_at FROM compute_request WHERE id = ?", LocalDateTime.class, requestId);

            var again = Flyway.configure().dataSource(db.businessUrl(), ComputeMysqlTestDatabase.username(),
                    ComputeMysqlTestDatabase.password()).locations("classpath:db/a800-compute").load().migrate();
            assertEquals(0, again.migrationsExecuted);

            assertEquals(1, business.queryForObject(
                    "SELECT COUNT(*) FROM compute_request WHERE client_request_key = 'm17-restart'", Integer.class));
            assertEquals(submittedAt, business.queryForObject(
                    "SELECT submitted_at FROM compute_request WHERE id = ?", LocalDateTime.class, requestId));
            assertEquals(groups, business.queryForObject("SELECT COUNT(*) FROM gpu_group", Integer.class));
            assertEquals(devices, business.queryForObject("SELECT COUNT(*) FROM gpu_device", Integer.class));
            assertEquals(initialHash, platform.queryForObject(
                    "SELECT password_hash FROM bm_user WHERE username = 'admin'", String.class));
        }
    }
}
