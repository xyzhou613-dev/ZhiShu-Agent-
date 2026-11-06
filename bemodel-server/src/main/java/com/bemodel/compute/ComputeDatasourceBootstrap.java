package com.bemodel.compute;

import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.datasource.service.SchemaScanService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("compute")
@DependsOn("computeStore")
final class ComputeDatasourceBootstrap implements ApplicationRunner {
    private static final String CODE = "A800_COMPUTE";

    private final DatasourceService datasources;
    private final SchemaScanService scanner;
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    ComputeDatasourceBootstrap(DatasourceService datasources, SchemaScanService scanner,
                               @Value("${COMPUTE_MYSQL_HOST:127.0.0.1}") String host,
                               @Value("${COMPUTE_MYSQL_PORT:3306}") int port,
                               @Value("${COMPUTE_MYSQL_USERNAME}") String username,
                               @Value("${COMPUTE_MYSQL_PASSWORD}") String password) {
        this.datasources = datasources;
        this.scanner = scanner;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (datasources.getByCode(CODE) == null) {
            var source = new Datasource();
            source.setDsCode(CODE);
            source.setDsName("A800 算力样例库");
            source.setProductName("算力管理");
            source.setDbType("MySQL");
            source.setHost(host);
            source.setPort(port);
            source.setDbName("a800_compute");
            source.setUsername(username);
            source.setPassword(password);
            datasources.create(source);
        }
        scanner.scan(CODE);
    }
}
