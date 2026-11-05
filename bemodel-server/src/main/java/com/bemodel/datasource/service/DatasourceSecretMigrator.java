package com.bemodel.datasource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bemodel.common.CryptoService;
import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.mapper.DatasourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把 bm_datasource 里的明文密码原地加密为 AES-256-GCM（ENC: 前缀）。
 * 幂等：已加密的行跳过。先于 DataSeeder 执行（decrypt 对明文兼容，顺序其实无关）。
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class DatasourceSecretMigrator implements ApplicationRunner {

    private final DatasourceMapper datasourceMapper;
    private final CryptoService cryptoService;

    @Override
    public void run(ApplicationArguments args) {
        List<Datasource> all = datasourceMapper.selectList(
                new LambdaQueryWrapper<Datasource>().isNotNull(Datasource::getPassword));
        int encrypted = 0;
        for (Datasource ds : all) {
            if (!ds.getPassword().startsWith(CryptoService.PREFIX)) {
                ds.setPassword(cryptoService.encrypt(ds.getPassword()));
                datasourceMapper.updateById(ds);
                encrypted++;
            }
        }
        if (encrypted > 0) {
            log.info("数据源密码原地加密完成：{} 行明文 → AES-256-GCM", encrypted);
        }
    }
}
