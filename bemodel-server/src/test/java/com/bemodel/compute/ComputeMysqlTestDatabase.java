package com.bemodel.compute;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.sql.DriverManager;
import java.util.UUID;

/** Creates its own databases; the database in the supplied URL is never migrated or dropped. */
record ComputeMysqlTestDatabase(String platformUrl, String businessUrl, String platformName, String businessName,
                                JdbcTemplate platformJdbc, JdbcTemplate businessJdbc)
        implements AutoCloseable {
    static ComputeMysqlTestDatabase create() throws Exception {
        return create(null);
    }

    static ComputeMysqlTestDatabase create(String businessTarget) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String platform = "m01_test_platform_" + suffix;
        String business = "m01_test_business_" + suffix;
        var databases = new ComputeMysqlTestDatabase(schemaUrl(platform), schemaUrl(business), platform, business,
                jdbc(schemaUrl(platform)),jdbc(schemaUrl(business)));
        try (var connection = DriverManager.getConnection(serverUrl(), username(), password());
             var statement = connection.createStatement()) {
            if (!connection.getMetaData().getDatabaseProductName().equals("MySQL")
                    || connection.getMetaData().getDatabaseMajorVersion() < 8) {
                throw new IllegalStateException("M-01 integration tests require MySQL 8+");
            }
            try {
                statement.executeUpdate("CREATE DATABASE `" + platform + "` CHARACTER SET utf8mb4");
                statement.executeUpdate("CREATE DATABASE `" + business + "` CHARACTER SET utf8mb4");
                var migration = Flyway.configure().dataSource(databases.businessUrl(), username(), password())
                        .locations("classpath:db/a800-compute");
                if (businessTarget != null) migration.target(businessTarget);
                migration.load().migrate();
                return databases;
            } catch (Exception e) {
                databases.close();
                throw e;
            }
        }
    }

    JdbcTemplate platform() {
        return platformJdbc;
    }

    JdbcTemplate business() {
        return businessJdbc;
    }

    @Override
    public void close() throws Exception {
        // Never accept arbitrary schema names in DROP statements, including from the supplied URL.
        if (!platformName.matches("m01_test_platform_[a-f0-9]{32}")
                || !businessName.matches("m01_test_business_[a-f0-9]{32}")) {
            throw new IllegalStateException("Refusing to clean a non-test schema");
        }
        try (var connection = DriverManager.getConnection(serverUrl(), username(), password());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS `" + businessName + "`");
            statement.executeUpdate("DROP DATABASE IF EXISTS `" + platformName + "`");
        }
    }

    private static String serverUrl() {
        return System.getenv("COMPUTE_ONTOLOGY_MYSQL_URL");
    }

    static String username() {
        return System.getenv("COMPUTE_MYSQL_USERNAME");
    }

    static String password() {
        return System.getenv("COMPUTE_MYSQL_PASSWORD");
    }

    private static String schemaUrl(String name) throws Exception {
        var uri = URI.create(serverUrl().substring("jdbc:".length()));
        return "jdbc:" + new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/" + name, uri.getQuery(), null);
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, username(), password()));
    }
}
