package com.bemodel.lab;

import com.bemodel.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 四实验剧本完整性:键/锚点/三臂文案齐全,与 SandboxTools.forExperiment 的键对齐 */
@SpringBootTest
class LabExperimentRegistryTest {

    @Autowired
    private LabExperimentRegistry registry;

    @Test
    void fourExperimentsWithAlignedKeys() {
        assertEquals(List.of("GATE", "ADVERSARIAL", "REFUND", "TRAVERSE"),
                registry.all().stream().map(LabExperimentRegistry.LabExperiment::key).toList());
    }

    @Test
    void anchorsMatchOntologyProofs() {
        assertEquals(List.of("RULE-QC-007", "AX-007"), registry.get("GATE").anchorKeys());
        assertEquals(List.of("ACT-"), registry.get("ADVERSARIAL").anchorKeys());
        assertEquals(List.of("ACT-REFUND"), registry.get("REFUND").anchorKeys());
        assertEquals(List.of("staff_trace"), registry.get("TRAVERSE").anchorKeys());
    }

    @Test
    void promptsAndCAnswerPresent() {
        for (LabExperimentRegistry.LabExperiment e : registry.all()) {
            assertFalse(e.question().isBlank());
            assertFalse(e.aPrompt().isBlank());
            assertFalse(e.bPromptForced().isBlank());
            assertFalse(e.bPromptGentle().isBlank());
            assertFalse(e.cAnswer().isBlank());
        }
    }

    @Test
    void unknownKeyThrows() {
        assertThrows(BizException.class, () -> registry.get("NOPE"));
    }
}
