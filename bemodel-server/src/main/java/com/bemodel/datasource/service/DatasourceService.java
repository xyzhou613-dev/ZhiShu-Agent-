package com.bemodel.datasource.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bemodel.common.BizException;
import com.bemodel.common.CryptoService;
import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.mapper.DatasourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class DatasourceService extends ServiceImpl<DatasourceMapper, Datasource> {

    private final CryptoService cryptoService;
    private final Map<String, JdbcTemplate> jdbcCache = new ConcurrentHashMap<>();

    public Datasource getByCode(String dsCode) {
        return lambdaQuery().eq(Datasource::getDsCode, dsCode).one();
    }

    /** 按注册信息动态建立到产品库的 JDBC 连接（只读用途；存储侧密码为 ENC: 密文，连接前解密） */
    public JdbcTemplate jdbc(String dsCode) {
        return jdbcCache.computeIfAbsent(dsCode, code -> {
            Datasource ds = getByCode(code);
            if (ds == null) {
                throw new BizException("数据源不存在: " + code);
            }
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(String.format(
                    "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                    ds.getHost(), ds.getPort(), ds.getDbName()));
            dataSource.setUsername(ds.getUsername());
            dataSource.setPassword(cryptoService.decrypt(ds.getPassword()));
            return new JdbcTemplate(dataSource);
        });
    }

    public boolean testConnection(Datasource ds) {
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            dataSource.setUrl(String.format(
                    "jdbc:mysql://%s:%d/%s?connectTimeout=5000&socketTimeout=5000&useSSL=false&allowPublicKeyRetrieval=true",
                    ds.getHost(), ds.getPort(), ds.getDbName()));
            dataSource.setUsername(ds.getUsername());
            dataSource.setPassword(cryptoService.decrypt(ds.getPassword()));
            new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 新建数据源：明文密码落库前加密；返回体掩码，不回显密文 */
    public Datasource create(Datasource ds) {
        ds.setPassword(cryptoService.encrypt(ds.getPassword()));
        save(ds);
        ds.setPassword("****");
        return ds;
    }

    /** 列表永远不返回密码字段（掩码 ****） */
    public List<Datasource> listAll() {
        List<Datasource> all = lambdaQuery().orderByAsc(Datasource::getDsCode).list();
        all.forEach(ds -> ds.setPassword("****"));
        return all;
    }
}
