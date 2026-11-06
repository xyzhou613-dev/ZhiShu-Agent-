package com.bemodel.compute;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration(proxyBeanMethods = false)
@Profile("compute")
class ComputeDatabase {
    record Store(JdbcTemplate jdbc) {}

    @Bean
    Store computeStore(
            @Value("${COMPUTE_MYSQL_HOST:127.0.0.1}") String host,
            @Value("${COMPUTE_MYSQL_PORT:3306}") int port,
            @Value("${COMPUTE_MYSQL_USERNAME}") String username,
            @Value("${COMPUTE_MYSQL_PASSWORD}") String password) {
        String url = "jdbc:mysql://%s:%d/a800_compute?createDatabaseIfNotExist=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                .formatted(host, port);
        var source = new DriverManagerDataSource(url, username, password);
        Flyway.configure().dataSource(source).locations("classpath:db/a800-compute").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        return new Store(jdbc);
    }
}
