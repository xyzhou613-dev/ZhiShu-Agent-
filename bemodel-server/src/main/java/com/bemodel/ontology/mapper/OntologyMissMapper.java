package com.bemodel.ontology.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bemodel.ontology.entity.OntologyMiss;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface OntologyMissMapper extends BaseMapper<OntologyMiss> {

    /** 单语句 upsert：唯一键 (term,kind) 撞行则原子地 count+1、刷新 last_seen（无 check-then-act 竞态） */
    @Insert("INSERT INTO bm_ontology_miss (term, kind, source, count, dismissed, revoked, first_seen, last_seen)"
            + " VALUES (#{term}, #{kind}, #{source}, 1, 0, 0, NOW(), NOW())"
            + " ON DUPLICATE KEY UPDATE count = count + 1, last_seen = NOW()")
    int upsert(@Param("term") String term, @Param("kind") String kind, @Param("source") String source);
}
