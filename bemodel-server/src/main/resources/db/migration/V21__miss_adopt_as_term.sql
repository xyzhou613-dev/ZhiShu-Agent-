-- =============================================================
-- V21: 本体增长回路·第三种处置 —— miss 可挂为现有概念的方言术语
--      adopted_as 区分采纳形态：CONCEPT 新建概念 / TERM 挂术语
-- =============================================================

ALTER TABLE bm_ontology_miss
    ADD COLUMN adopted_as VARCHAR(16) NULL COMMENT '采纳形态：CONCEPT 新建概念 / TERM 挂为现有概念术语' AFTER adopted_concept_code;
