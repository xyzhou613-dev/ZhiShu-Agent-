-- V37: 公理视觉编码正例补缺(spec 2026-09-21 §7)——四条零正例公理各补语义真实的种子。
-- 只给语义上真实成立的关系置位;不新增概念(裸概念会触发发布门禁 CONCEPT_NO_ATTR WARN 污染)。
-- 对称选 STAFF 自环:同事关系天然对称;自环边由前端 curveness 拉开成弧可见(axiomEdge.js)。

-- 逆函数:每次就诊只属于一个患者,由就诊可反推唯一患者
UPDATE bm_relation SET is_inverse_functional = 1
 WHERE from_concept = 'PATIENT' AND to_concept = 'INP_VISIT' AND relation_name = '发生就诊';

-- 函数:每次就诊至多一张出院结算单
UPDATE bm_relation SET is_functional = 1
 WHERE from_concept = 'INP_VISIT' AND to_concept = 'SETTLEMENT' AND relation_name = '出院结算';

-- 反对称:费用明细不会反向产生医嘱
UPDATE bm_relation SET is_asymmetric = 1
 WHERE from_concept = 'MEDICAL_ORDER' AND to_concept = 'FEE_DETAIL' AND relation_name = '产生费用';

-- 对称:A 是 B 的同事,则 B 也是 A 的同事(自环,演示对称公理)
INSERT INTO bm_relation (from_concept, to_concept, relation_name, description, is_symmetric)
VALUES ('STAFF', 'STAFF', '同事', '同事关系天然对称：A是B的同事，则B也是A的同事（对称公理正例）', 1);
