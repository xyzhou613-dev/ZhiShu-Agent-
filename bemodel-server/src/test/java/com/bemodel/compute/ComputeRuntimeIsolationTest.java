package com.bemodel.compute;

import com.bemodel.mcp.BemodelMcpTools;
import com.bemodel.mcp.McpConfig;
import com.bemodel.notice.InspectScheduler;
import com.bemodel.ontology.ProposalScheduler;
import com.bemodel.seed.DataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** M-17：医疗初始化、定时任务、MCP 工具入口和运行开关的静态边界。 */
class ComputeRuntimeIsolationTest {
    @Test
    void medicalInitializationSchedulersAndToolEntryAreDisabledInComputeProfile() {
        assertProfile("!compute", DataSeeder.class);
        assertProfile("!compute", InspectScheduler.class);
        assertProfile("!compute", ProposalScheduler.class);
        assertProfile("!compute", McpConfig.class);
        assertProfile("!compute", BemodelMcpTools.class);
    }

    @Test
    void computeRuntimeSettingsAndMedicalFilterOnlyExistInComputeProfile() {
        assertProfile("compute", ComputeRuntimeSettings.class);
        assertProfile("compute", MedicalApiIsolationFilter.class);
        assertProfile("compute", ComputeRuntimeGuard.class);
        assertProfile("compute", ComputeRuntimeController.class);
    }

    @Test
    void exampleConfigurationMakesModeAndRestartProtectionExplicit() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/application-compute.yml.example"),
                StandardCharsets.UTF_8);
        assertTrue(config.contains("mode: ${COMPUTE_RUNTIME_MODE:SIMULATION}"));
        assertTrue(config.contains("demo-dataset: ${COMPUTE_DEMO_DATASET:A800_DEMO_FIXTURE}"));
        assertTrue(config.contains("medical-apis-enabled: ${COMPUTE_MEDICAL_APIS_ENABLED:false}"));
        assertTrue(config.contains("recheck-enabled: ${COMPUTE_QUEUE_RECHECK_ENABLED:false}"));
        assertTrue(config.contains("clean-disabled: true"));
        assertTrue(config.contains("validate-on-migrate: true"));
    }

    private static void assertProfile(String expected, Class<?> type) {
        Profile profile = type.getAnnotation(Profile.class);
        assertNotNull(profile, type.getName() + " 缺少 @Profile");
        assertEquals(1, profile.value().length, type.getName());
        assertEquals(expected, profile.value()[0], type.getName());
    }
}
