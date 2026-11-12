package com.bemodel.compute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ComputeRuntimeSettingsTest {
    private static ComputeRuntimeSettings settings(String mode, boolean medicalApis, boolean queueRecheck) {
        return new ComputeRuntimeSettings(mode, "a800-demo", "A800_DEMO_FIXTURE", medicalApis, queueRecheck);
    }

    @Test
    void defaultsToSimulationWithMedicalApisIsolated() {
        var settings = settings("SIMULATION", false, false);
        assertEquals(ComputeRuntimeSettings.Mode.SIMULATION, settings.mode());
        assertTrue(settings.simulation());
        assertEquals("a800-demo", settings.nodeKey());
        assertEquals("A800_DEMO_FIXTURE", settings.demoDataset());
        assertFalse(settings.medicalApisEnabled());
        assertFalse(settings.queueRecheckEnabled());
        assertEquals(ComputeRuntimeSettings.ISOLATED_MEDICAL_PATHS, settings.isolatedMedicalPaths());
    }

    @Test
    void acceptsLowercaseSimulationButRefusesRealUntilAdaptersExist() {
        assertTrue(settings("simulation", false, false).simulation());
        var error = assertThrows(IllegalStateException.class, () -> settings("real", false, false));
        assertTrue(error.getMessage().contains("REAL"));
        assertTrue(error.getMessage().contains("N-13"));
    }

    @Test
    void rejectsUnknownModeAndBlankNodeKey() {
        assertThrows(IllegalStateException.class, () -> settings("DEMO", false, false));
        assertThrows(IllegalStateException.class, () -> new ComputeRuntimeSettings(
                "SIMULATION", " ", "A800_DEMO_FIXTURE", false, false));
        assertThrows(IllegalStateException.class, () -> new ComputeRuntimeSettings(
                "SIMULATION", "a800-demo", "", false, false));
    }

    @Test
    void medicalApisCanBeExplicitlyEnabledForJointDemo() {
        assertTrue(settings("SIMULATION", true, true).medicalApisEnabled());
        assertTrue(settings("SIMULATION", true, true).queueRecheckEnabled());
    }

    @Test
    void isolatesOnlyMedicalPrefixesAtPathBoundaries() {
        assertEquals("/api/cs", ComputeRuntimeSettings.matchedIsolatedPath("/api/cs"));
        assertEquals("/api/cs", ComputeRuntimeSettings.matchedIsolatedPath("/api/cs/ask"));
        assertNull(ComputeRuntimeSettings.matchedIsolatedPath("/api/csharp"));
        assertEquals("/mcp", ComputeRuntimeSettings.matchedIsolatedPath("/mcp"));
        assertEquals("/mcp", ComputeRuntimeSettings.matchedIsolatedPath("/mcp/tools"));
        assertNull(ComputeRuntimeSettings.matchedIsolatedPath("/mcp-extra"));
        assertEquals("/api/rdf/patient", ComputeRuntimeSettings.matchedIsolatedPath("/api/rdf/patient/123"));
        assertNull(ComputeRuntimeSettings.matchedIsolatedPath("/api/rdf/ontology"));
    }

    @Test
    void neverBlocksComputeOrSharedPlatformApis() {
        for (String path : ComputeRuntimeSettings.ISOLATED_MEDICAL_PATHS) {
            assertFalse(path.startsWith("/api/compute"), path);
        }
        for (String path : new String[]{"/api/compute/groups", "/api/compute/runtime", "/api/concept/list",
                "/api/datasource/list", "/api/mapping/list", "/api/gov/issues", "/api/auth/login"}) {
            assertNull(ComputeRuntimeSettings.matchedIsolatedPath(path), path);
        }
    }
}
