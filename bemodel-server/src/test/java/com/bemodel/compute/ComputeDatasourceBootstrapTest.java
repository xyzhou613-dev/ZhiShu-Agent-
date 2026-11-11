package com.bemodel.compute;
import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.datasource.service.SchemaScanService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComputeDatasourceBootstrapTest {
    @Test void newRegistrationUsesResolvedBusinessSchemaAndCredentials() {
        var ds=mock(DatasourceService.class);var scanner=mock(SchemaScanService.class);
        try(var pool=new HikariDataSource()) {
            pool.setUsername("business-test-user");pool.setPassword("business-test-secret");
            bootstrap(ds,scanner,pool).run(null);
            var captured=ArgumentCaptor.forClass(Datasource.class);verify(ds).create(captured.capture());
            var registered=captured.getValue();assertEquals("separate_business",registered.getDbName());
            assertEquals("other.example",registered.getHost());assertEquals(3307,registered.getPort());
            assertEquals("business-test-user",registered.getUsername());assertEquals("business-test-secret",registered.getPassword());
            verify(scanner).scan("A800_COMPUTE");
        }
    }
    @Test void existingMatchingRegistrationAndReadonlyCredentialsArePreserved() {
        var ds=mock(DatasourceService.class);var scanner=mock(SchemaScanService.class);var row=registered();
        row.setUsername("read-only-user");when(ds.getByCode("A800_COMPUTE")).thenReturn(row);
        try(var pool=new HikariDataSource()) {bootstrap(ds,scanner,pool).run(null);verify(ds,never()).create(any());verify(scanner).scan("A800_COMPUTE");assertEquals("read-only-user",row.getUsername());}
    }
    @Test void mismatchedRegistrationCannotSilentlyScanADifferentDatabase() {
        var ds=mock(DatasourceService.class);var scanner=mock(SchemaScanService.class);var row=registered();row.setDbName("old_database");
        when(ds.getByCode("A800_COMPUTE")).thenReturn(row);
        try(var pool=new HikariDataSource()) {assertThrows(RuntimeException.class,()->bootstrap(ds,scanner,pool).run(null));verifyNoInteractions(scanner);verify(ds,never()).create(any());}
    }
    private Datasource registered(){var row=new Datasource();row.setHost("other.example");row.setPort(3307);row.setDbName("separate_business");return row;}
    private ComputeDatasourceBootstrap bootstrap(DatasourceService ds,SchemaScanService scanner,HikariDataSource pool){
        var endpoint=new ComputeDatabase.Endpoint("other.example",3307,"jdbc:mysql://other.example:3307/separate_business?connectionTimeZone=UTC");
        var store=new ComputeDatabase.Store(new JdbcTemplate(new DriverManagerDataSource(endpoint.jdbcUrl())),endpoint);
        return new ComputeDatasourceBootstrap(ds,scanner,store,pool);
    }
}
