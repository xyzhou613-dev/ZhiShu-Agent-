package com.bemodel.compute;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@Profile("compute")
class ComputeDatabase {
    record Endpoint(String host, int port, String jdbcUrl) {
        static Endpoint fromPlatformUrl(String platformUrl) {
            if (!platformUrl.startsWith("jdbc:mysql://")) {
                throw new IllegalArgumentException("算力版需要单主机 MySQL JDBC 地址");
            }
            URI uri = URI.create(platformUrl.substring("jdbc:".length()));
            if (uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("算力版 MySQL 地址必须提供主机，账号密码通过数据源属性配置");
            }
            int port = uri.getPort() < 0 ? 3306 : uri.getPort();
            String query = uri.getRawQuery();
            String url = "jdbc:mysql://" + uri.getRawAuthority() + "/a800_compute";
            return new Endpoint(uri.getHost(), port, query == null ? url : url + "?" + query);
        }
    }

    record Store(JdbcTemplate jdbc, Endpoint endpoint) {}

    @Bean
    Store computeStore(DataSourceProperties properties) {
        Endpoint endpoint = Endpoint.fromPlatformUrl(properties.determineUrl());
        var source = new DriverManagerDataSource(endpoint.jdbcUrl(),
                properties.determineUsername(), properties.determinePassword());
        Flyway.configure().dataSource(source).locations("classpath:db/a800-compute").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        return new Store(jdbc, endpoint);
    }
}
