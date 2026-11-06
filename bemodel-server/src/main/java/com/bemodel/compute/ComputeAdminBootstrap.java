package com.bemodel.compute;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("compute")
final class ComputeAdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String password;

    ComputeAdminBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder,
                          @Value("${COMPUTE_ADMIN_PASSWORD:}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bm_user WHERE role = 'ADMIN'", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        if (password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("算力版首次启动需要设置 COMPUTE_ADMIN_PASSWORD，至少 12 字符且不超过 72 字节");
        }
        jdbc.update("INSERT INTO bm_user (username, password_hash, display_name, role) VALUES (?, ?, ?, ?)",
                "admin", encoder.encode(password), "算力管理员", "ADMIN");
    }
}
