package com.bemodel.compute;

import com.bemodel.seed.DataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ComputeProfileTest {
    @Test
    void usesItsOwnSchemaWithoutMedicalInitialization() throws Exception {
        String config = new ClassPathResource("application-compute.yml").getContentAsString(StandardCharsets.UTF_8);
        String migration = new ClassPathResource("db/compute-platform/V1__platform_schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(config.contains("classpath:db/compute-platform"));
        assertTrue(config.contains("/bemodel_compute_platform?"));
        assertFalse(migration.matches("(?is).*\\b(?:INSERT INTO|CREATE DATABASE|demo_his\\.)\\b.*"));
        assertArrayEquals(new String[]{"!compute"}, DataSeeder.class.getAnnotation(Profile.class).value());
    }
}
