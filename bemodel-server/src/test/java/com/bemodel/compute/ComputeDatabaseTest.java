package com.bemodel.compute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComputeDatabaseTest {
    @Test
    void derivesBusinessSchemaFromResolvedPlatformConnection() {
        var endpoint = ComputeDatabase.Endpoint.fromPlatformUrl(
                "jdbc:mysql://compute.example:53778/bemodel_compute_platform?connectionTimeZone=UTC&sslMode=REQUIRED");
        assertEquals("compute.example", endpoint.host());
        assertEquals(53778, endpoint.port());
        assertEquals("jdbc:mysql://compute.example:53778/a800_compute?connectionTimeZone=UTC&sslMode=REQUIRED",
                endpoint.jdbcUrl());
    }

    @Test
    void usesDefaultPortWhenPlatformUrlDoesNotSpecifyOne() {
        var endpoint = ComputeDatabase.Endpoint.fromPlatformUrl(
                "jdbc:mysql://localhost/bemodel_compute_platform");
        assertEquals(3306, endpoint.port());
        assertEquals("jdbc:mysql://localhost/a800_compute", endpoint.jdbcUrl());
    }
}
