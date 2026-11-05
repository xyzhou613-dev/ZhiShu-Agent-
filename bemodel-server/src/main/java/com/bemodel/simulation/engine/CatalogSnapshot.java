package com.bemodel.simulation.engine;

import java.util.List;
import java.util.Map;

/** 传导目录快照:关系边+映射列+属性中文名+概念名,来自平台库只读一次性装入;纯 record 可单测直构 */
public record CatalogSnapshot(List<Rel> relations, List<Col> cols,
                              Map<String, String> attrNames, Map<String, String> conceptNames) {

    /** 关系边:from—name→to */
    public record Rel(String from, String to, String name) {}

    /** 映射列:某表某列 → (概念,属性)。跨概念映射(dispense_record.order_id→MEDICAL_ORDER)即外键 */
    public record Col(String table, String column, String concept, String attrCode) {}

    /** concept 的全部邻接边(无向扩散:入边"谁喂我"+出边"我喂谁";relationChain 恒按真实方向渲染) */
    public List<Rel> neighbors(String concept) {
        return relations.stream().filter(r -> r.from().equals(concept) || r.to().equals(concept)).toList();
    }

    /** 概念名下的全部映射列——含别表列指向本概念的跨概念引用(如 dispense_record.patient_no→INP_VISIT),它们同样是该概念可用的查询依据 */
    public List<Col> ownCols(String concept) {
        return cols.stream().filter(c -> c.concept().equals(concept)).toList();
    }

    /** 一张表上的全部映射列(传导步收割外键值用) */
    public List<Col> colsOnTable(String table) {
        return cols.stream().filter(c -> c.table().equals(table)).toList();
    }

    public String attrName(String concept, String attrCode) {
        return attrNames.get(concept + "." + attrCode);
    }

    public String conceptName(String code) {
        return conceptNames.getOrDefault(code, code);
    }
}
