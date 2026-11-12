package com.bemodel.compute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * M-17 启动检查：把运行模式、医疗隔离和重启保护写成启动时可见的事实，避免只存在于说明文档。
 */
@Component
@Profile("compute")
final class ComputeRuntimeGuard implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ComputeRuntimeGuard.class);

    private final ComputeRuntimeSettings settings;

    ComputeRuntimeGuard(ComputeRuntimeSettings settings) {
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOG.info("算力启动检查通过: 模式={} 模拟={} 医疗接口={} 医疗初始化=DISABLED 医疗定时任务=DISABLED "
                        + "队列自动重评={} 演示数据={}；迁移只执行一次，已有管理员、数据源登记和业务数据不会被启动覆盖",
                settings.mode(), settings.simulation() ? "YES" : "NO",
                settings.medicalApisEnabled() ? "ENABLED" : "ISOLATED",
                settings.queueRecheckEnabled() ? "ENABLED" : "DISABLED",
                settings.demoDataset());
    }
}
