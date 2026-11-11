package com.bemodel.compute;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.util.Objects;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@Profile("compute")
class ComputeDatabase {
    record Endpoint(String host, int port, String jdbcUrl) {
        static Endpoint business(String platformUrl,String configuredUrl) {
            Endpoint result;
            if(configuredUrl==null||configuredUrl.isBlank()) result=fromPlatformUrl(platformUrl);
            else {
                if(!configuredUrl.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("业务库需要单主机 MySQL 地址");
                URI uri=URI.create(configuredUrl.substring(5));
                if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getPath()==null||uri.getPath().length()<2)
                    throw new IllegalArgumentException("业务库必须提供单主机和独立库名，凭据单独配置");
                result=new Endpoint(uri.getHost(),uri.getPort()<0?3306:uri.getPort(),configuredUrl);
            }
            URI platform=URI.create(platformUrl.substring(5)),business=URI.create(result.jdbcUrl().substring(5));
            if(Objects.equals(platform.getHost(),business.getHost()) && (platform.getPort()<0?3306:platform.getPort())==(business.getPort()<0?3306:business.getPort()) && Objects.equals(platform.getPath(),business.getPath()))
                throw new IllegalArgumentException("平台库和算力业务库必须分离");
            return result;
        }
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

    record Store(JdbcTemplate jdbc, Endpoint endpoint,PlatformTransactionManager transactionManager) {
        Store(JdbcTemplate jdbc,Endpoint endpoint) {this(jdbc,endpoint,new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));}
        Store {
            if(transactionManager instanceof DataSourceTransactionManager manager && manager.getDataSource()!=jdbc.getDataSource())
                throw new IllegalArgumentException("业务 JDBC 与事务管理器必须使用同一数据源");
        }
        TransactionTemplate transactions(){return new TransactionTemplate(transactionManager);}
    }

    @Bean(name={"dataSource","platformDataSource"})
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource platformDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name={"jdbcTemplate","platformJdbcTemplate"}) @Primary
    JdbcTemplate platformJdbc(@Qualifier("dataSource") DataSource source){return new JdbcTemplate(source);}

    @Bean(name={"transactionManager","platformTransactionManager"}) @Primary
    DataSourceTransactionManager platformTransactions(@Qualifier("dataSource") DataSource source){return new DataSourceTransactionManager(source);}

    @Bean(name="computeDataSource",destroyMethod="close")
    HikariDataSource computeDataSource(DataSourceProperties platform,
            @Value("${compute.datasource.jdbc-url:}") String url,
            @Value("${compute.datasource.username:}") String username,
            @Value("${compute.datasource.password:}") String password,
            @Value("${compute.datasource.maximum-pool-size:4}") int maxPool,
            @Value("${compute.datasource.minimum-idle:1}") int minimumIdle) {
        Endpoint endpoint=Endpoint.business(platform.determineUrl(),url);
        var config=new HikariConfig();config.setPoolName("compute-business");config.setJdbcUrl(endpoint.jdbcUrl());
        config.setUsername(username.isBlank()?platform.determineUsername():username);
        config.setPassword(password.isBlank()?platform.determinePassword():password);
        config.setMaximumPoolSize(maxPool);config.setMinimumIdle(minimumIdle);
        config.setConnectionTimeout(8000);config.setValidationTimeout(2000);config.setMaxLifetime(120000);
        config.setIdleTimeout(60000);config.setKeepaliveTime(30000);config.setConnectionInitSql("SET time_zone = '+00:00'");
        return new HikariDataSource(config);
    }

    @Bean(name="computeJdbcTemplate")
    JdbcTemplate businessJdbc(@Qualifier("computeDataSource") DataSource source) {
        var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(10);return jdbc;
    }
    @Bean(name="computeTransactionManager")
    DataSourceTransactionManager businessTransactions(@Qualifier("computeDataSource") DataSource source){return new DataSourceTransactionManager(source);}

    @Bean
    Store computeStore(DataSourceProperties properties,@Qualifier("computeDataSource") HikariDataSource source,
                       @Qualifier("computeJdbcTemplate") JdbcTemplate jdbc,@Qualifier("computeTransactionManager") PlatformTransactionManager manager) {
        Endpoint endpoint=Endpoint.business(properties.determineUrl(),source.getJdbcUrl());
        Flyway.configure().dataSource(source).locations("classpath:db/a800-compute").load().migrate();
        return new Store(jdbc,endpoint,manager);
    }
}
