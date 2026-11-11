package com.bemodel.compute;

import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.datasource.service.SchemaScanService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import com.zaxxer.hikari.HikariDataSource;
import com.bemodel.common.BizException;
import org.springframework.beans.factory.annotation.Qualifier;
import java.net.URI;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("compute")
final class ComputeDatasourceBootstrap implements ApplicationRunner {
    private static final String CODE = "A800_COMPUTE";

    private final DatasourceService datasources;
    private final SchemaScanService scanner;
    private final ComputeDatabase.Store store;
    private final HikariDataSource businessSource;

    ComputeDatasourceBootstrap(DatasourceService datasources, SchemaScanService scanner,
                               ComputeDatabase.Store store,@Qualifier("computeDataSource") HikariDataSource businessSource) {
        this.datasources = datasources;
        this.scanner = scanner;
        this.store = store;
        this.businessSource = businessSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        String database=URI.create(store.endpoint().jdbcUrl().substring("jdbc:".length())).getPath().substring(1);
        var registered=datasources.getByCode(CODE);
        if (registered == null) {
            var source = new Datasource();
            source.setDsCode(CODE);
            source.setDsName("A800 算力样例库");
            source.setProductName("算力管理");
            source.setDbType("MySQL");
            source.setHost(store.endpoint().host());
            source.setPort(store.endpoint().port());
            source.setDbName(database);
            source.setUsername(businessSource.getUsername());
            source.setPassword(businessSource.getPassword());
            datasources.create(source);
        } else if (!Objects.equals(registered.getHost(),store.endpoint().host())
                || !Objects.equals(registered.getPort(),store.endpoint().port()) || !Objects.equals(registered.getDbName(),database)) {
            throw new BizException("A800_COMPUTE 的登记地址与运行业务库不一致，请核对映射数据源；不会自动覆盖已有登记或凭据");
        }
        scanner.scan(CODE);
    }
}
