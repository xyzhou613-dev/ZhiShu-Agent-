package com.bemodel.compute;

import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.datasource.service.SchemaScanService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("compute")
final class ComputeDatasourceBootstrap implements ApplicationRunner {
    private static final String CODE = "A800_COMPUTE";

    private final DatasourceService datasources;
    private final SchemaScanService scanner;
    private final ComputeDatabase.Store store;
    private final DataSourceProperties properties;

    ComputeDatasourceBootstrap(DatasourceService datasources, SchemaScanService scanner,
                               ComputeDatabase.Store store, DataSourceProperties properties) {
        this.datasources = datasources;
        this.scanner = scanner;
        this.store = store;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (datasources.getByCode(CODE) == null) {
            var source = new Datasource();
            source.setDsCode(CODE);
            source.setDsName("A800 算力样例库");
            source.setProductName("算力管理");
            source.setDbType("MySQL");
            source.setHost(store.endpoint().host());
            source.setPort(store.endpoint().port());
            source.setDbName("a800_compute");
            source.setUsername(properties.determineUsername());
            source.setPassword(properties.determinePassword());
            datasources.create(source);
        }
        scanner.scan(CODE);
    }
}
