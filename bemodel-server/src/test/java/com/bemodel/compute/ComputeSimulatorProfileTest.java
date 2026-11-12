package com.bemodel.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ComputeSimulatorProfileTest {
    ApplicationContextRunner context(ComputeRuntimeSettings settings){return new ApplicationContextRunner()
            .withInitializer(c->{c.getEnvironment().setActiveProfiles("compute");c.getBeanFactory().registerSingleton("runtimeSettings",settings);})
            .withBean(ComputeDatabase.Store.class,()->mock(ComputeDatabase.Store.class))
            .withBean(ObjectMapper.class,ObjectMapper::new)
            .withUserConfiguration(ComputeSimulatorService.class,ComputeSimulatorExecutor.class,ComputeSimulatorController.class);}
    ComputeRuntimeSettings settings(){return new ComputeRuntimeSettings("SIMULATION","a800-demo","fixture",false,false);}
    @Test void disabledByDefault(){context(settings()).run(c->{assertThat(c).doesNotHaveBean(ComputeSimulatorExecutor.class);assertThat(c).doesNotHaveBean(ComputeSimulatorController.class);});}
    @Test void explicitSimulationSwitchRegistersOnlyTheSupportedAdapter(){context(settings()).withPropertyValues("compute.simulator.enabled=true").run(c->{assertThat(c).hasSingleBean(ComputeSimulatorExecutor.class);var executor=c.getBean(ComputeSimulatorExecutor.class);assertThat(executor.supports("STOP")).isTrue();assertThat(executor.supports("HANDOVER")).isFalse();assertThat(executor.supports("FINALIZE")).isFalse();});}
    @Test void realModeCannotRegisterTheSimulator(){var real=mock(ComputeRuntimeSettings.class);when(real.simulation()).thenReturn(false);context(real).withPropertyValues("compute.simulator.enabled=true").run(c->{assertThat(c).hasFailed();assertThat(c.getStartupFailure()).hasStackTraceContaining("模拟适配器不能用于真实模式");});}
}
