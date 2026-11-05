package com.bemodel.lab;

import com.bemodel.lab.entity.LabRun;
import com.bemodel.lab.mapper.LabRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** V35 落库回环:实体字段与表结构对齐(MyBatis-Plus 驼峰映射) */
@SpringBootTest
class LabRunMapperTest {

    @Autowired
    private LabRunMapper mapper;

    @Test
    void insertAndLoadRoundtrip() {
        LabRun run = new LabRun();
        run.setExperimentKey("GATE");
        run.setQuestion("测试问题:三臂对照");
        run.setForceExecute(true);
        run.setStatus("QUEUED");
        run.setCreatedAt(LocalDateTime.now());
        mapper.insert(run);
        try {
            LabRun loaded = mapper.selectById(run.getId());
            assertEquals("GATE", loaded.getExperimentKey());
            assertEquals("QUEUED", loaded.getStatus());
            assertTrue(loaded.getForceExecute());
            assertNotNull(loaded.getCreatedAt());
        } finally {
            mapper.deleteById(run.getId());
        }
    }
}
