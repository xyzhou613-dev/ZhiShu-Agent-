package com.bemodel.simulation.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 传导引擎 golden:无向扩散层级/键族归一/外键接力/缺口断链/截断纪律/证据组装(纯逻辑,零 Spring 零 DB) */
class PropagationEngineTest {

    private final PropagationEngine engine = new PropagationEngine();

    /** 微缩真实本体(边方向照抄真实种子):DRUG_STOCK←STOCK_OUT←DISPENSE←MEDICAL_ORDER←INP_VISIT←PATIENT,MEDICAL_ORDER→FEE_DETAIL→SETTLEMENT,STOCK_IN→DRUG_STOCK,DRUG_PURCHASE→STOCK_IN */
    private CatalogSnapshot catalog() {
        List<CatalogSnapshot.Rel> rels = List.of(
                new CatalogSnapshot.Rel("STOCK_OUT", "DRUG_STOCK", "扣减库存"),
                new CatalogSnapshot.Rel("DISPENSE", "STOCK_OUT", "触发发药出库"),
                new CatalogSnapshot.Rel("MEDICAL_ORDER", "DISPENSE", "调剂发药"),
                new CatalogSnapshot.Rel("INP_VISIT", "MEDICAL_ORDER", "下达"),
                new CatalogSnapshot.Rel("MEDICAL_ORDER", "FEE_DETAIL", "产生费用"),
                new CatalogSnapshot.Rel("PATIENT", "INP_VISIT", "发生就诊"),
                new CatalogSnapshot.Rel("FEE_DETAIL", "SETTLEMENT", "汇总入"),
                new CatalogSnapshot.Rel("STOCK_IN", "DRUG_STOCK", "增加库存"),
                new CatalogSnapshot.Rel("DRUG_PURCHASE", "STOCK_IN", "验收入库"));
        List<CatalogSnapshot.Col> cols = List.of(
                new CatalogSnapshot.Col("drug_stock", "drug_code", "DRUG_STOCK", "drug_code"),
                new CatalogSnapshot.Col("drug_stock", "quantity", "DRUG_STOCK", "quantity"),
                new CatalogSnapshot.Col("stock_out", "out_id", "STOCK_OUT", "out_id"),
                new CatalogSnapshot.Col("stock_out", "drug_code", "STOCK_OUT", "drug_code"),
                new CatalogSnapshot.Col("dispense_record", "dispense_id", "DISPENSE", "dispense_id"),
                new CatalogSnapshot.Col("dispense_record", "item_code", "DISPENSE", "item_code"),
                new CatalogSnapshot.Col("dispense_record", "order_id", "MEDICAL_ORDER", "order_id"),
                new CatalogSnapshot.Col("dispense_record", "patient_no", "INP_VISIT", "visit_no"),
                new CatalogSnapshot.Col("medical_order", "order_id", "MEDICAL_ORDER", "order_id"),
                new CatalogSnapshot.Col("medical_order", "inhos_no", "INP_VISIT", "visit_no"),
                new CatalogSnapshot.Col("medical_order", "item_code", "MEDICAL_ORDER", "item_code"),
                new CatalogSnapshot.Col("fee_detail", "fee_id", "FEE_DETAIL", "fee_id"),
                new CatalogSnapshot.Col("fee_detail", "item_code", "FEE_DETAIL", "item_code"),
                new CatalogSnapshot.Col("fee_detail", "order_id", "MEDICAL_ORDER", "order_id"),
                new CatalogSnapshot.Col("fee_detail", "inhos_no", "INP_VISIT", "visit_no"),
                new CatalogSnapshot.Col("settlement", "settle_id", "SETTLEMENT", "settle_id"),
                new CatalogSnapshot.Col("settlement", "inhos_no", "INP_VISIT", "visit_no"),
                // 住院表:INP_VISIT 的主表(4 列),并给 PATIENT 供姓名键——对应真实 V8 形状
                new CatalogSnapshot.Col("inpatient", "visit_no", "INP_VISIT", "visit_no"),
                new CatalogSnapshot.Col("inpatient", "ward", "INP_VISIT", "ward"),
                new CatalogSnapshot.Col("inpatient", "dept", "INP_VISIT", "dept"),
                new CatalogSnapshot.Col("inpatient", "status", "INP_VISIT", "status"),
                new CatalogSnapshot.Col("inpatient", "patient_name", "PATIENT", "name"),
                // STOCK_IN 有表列但无 drug_code 映射 → 断链样本
                new CatalogSnapshot.Col("stock_in", "in_id", "STOCK_IN", "in_id"),
                new CatalogSnapshot.Col("stock_in", "quantity", "STOCK_IN", "quantity"));
        Map<String, String> attrNames = new LinkedHashMap<>();
        attrNames.put("DRUG_STOCK.drug_code", "药品编码");
        attrNames.put("DRUG_STOCK.quantity", "库存数量");
        attrNames.put("STOCK_OUT.drug_code", "药品编码");
        attrNames.put("DISPENSE.item_code", "药品编码");
        attrNames.put("MEDICAL_ORDER.item_code", "项目编码");
        attrNames.put("MEDICAL_ORDER.order_id", "医嘱号");
        attrNames.put("INP_VISIT.visit_no", "住院号");
        attrNames.put("INP_VISIT.ward", "病区");
        attrNames.put("INP_VISIT.dept", "科室");
        attrNames.put("INP_VISIT.status", "就诊状态");
        attrNames.put("PATIENT.name", "姓名");
        attrNames.put("FEE_DETAIL.item_code", "项目编码");
        Map<String, String> conceptNames = new LinkedHashMap<>();
        conceptNames.put("DRUG_STOCK", "药品库存");
        conceptNames.put("STOCK_OUT", "出库单");
        conceptNames.put("STOCK_IN", "入库单");
        conceptNames.put("DRUG_PURCHASE", "采购单");
        conceptNames.put("DISPENSE", "调剂发药");
        conceptNames.put("MEDICAL_ORDER", "医嘱");
        conceptNames.put("INP_VISIT", "住院就诊");
        conceptNames.put("FEE_DETAIL", "费用明细");
        conceptNames.put("SETTLEMENT", "结算");
        conceptNames.put("PATIENT", "患者");
        return new CatalogSnapshot(rels, cols, attrNames, conceptNames);
    }

    /** 假执行器:按 FROM 的表名应答,记录 SQL 与参数供断言 */
    private record Executed(String sql, List<Object> params) {}
    private final List<Executed> executed = new ArrayList<>();

    private StepQueryExecutor fakeDb() {
        return (sql, params) -> {
            executed.add(new Executed(sql, new ArrayList<>(params)));
            String table = sql.substring(sql.indexOf("FROM ") + 5).split(" ")[0];
            return switch (table) {
                case "drug_stock" -> List.of(row("drug_code", "D006", "quantity", 983));
                case "stock_out" -> List.of(row("out_id", "OUT001", "drug_code", "D006"));
                case "dispense_record" -> List.of(row("dispense_id", "DP001", "item_code", "D006",
                        "order_id", "ORD001", "patient_no", "ZY001"));
                case "medical_order" -> List.of(row("order_id", "ORD001", "inhos_no", "ZY001", "item_code", "D006"));
                case "fee_detail" -> List.of(row("fee_id", "FEE001", "item_code", "D006",
                        "order_id", "ORD001", "inhos_no", "ZY001"));
                case "settlement" -> List.of(row("settle_id", "ST001", "inhos_no", "ZY001"));
                case "inpatient" -> List.of(row("visit_no", "ZY001", "ward", "内一科病区",
                        "patient_name", "陈芳", "status", "出院"));
                default -> List.of();
            };
        };
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private LinkedHashMap<String, Set<String>> startKeys() {
        LinkedHashMap<String, Set<String>> keys = new LinkedHashMap<>();
        keys.put("药品编码", new LinkedHashSet<>(List.of("D006")));
        return keys;
    }

    private List<String> keyFamily() {
        return List.of("药品编码", "项目编码");
    }

    @Test
    void bfsLevelsFirstReachUndirected() {
        List<PropagationStep> steps = engine.propagate(catalog(), "DRUG_STOCK", keyFamily(), fakeDb(), startKeys());
        assertEquals("DRUG_STOCK", steps.get(0).concept());
        assertEquals(0, steps.get(0).level());
        assertEquals("STOCK_OUT", steps.get(1).concept());
        assertEquals(1, steps.get(1).level());
        // STOCK_IN 亦在 L1 首达(无向边 增加库存):无映射 → BLOCKED_GAP 断链占位,如实入镜不扩展
        assertEquals(PropagationStep.BLOCKED_GAP, steps.get(2).status());
        assertEquals("STOCK_IN", steps.get(2).concept());
        assertEquals(1, steps.get(2).level());
        assertEquals("DISPENSE", steps.get(3).concept());
        assertEquals(2, steps.get(3).level());
        assertEquals("MEDICAL_ORDER", steps.get(4).concept());
        assertEquals(3, steps.get(4).level());
        // 无向扩散首达层级:INP_VISIT/FEE_DETAIL 在 L4(一入边一出边各一条),PATIENT/SETTLEMENT 在 L5
        assertEquals(List.of("INP_VISIT", "FEE_DETAIL", "PATIENT", "SETTLEMENT"),
                steps.subList(5, steps.size()).stream().map(PropagationStep::concept).toList());
        assertEquals(4, steps.get(5).level());
        assertEquals(4, steps.get(6).level());
        assertEquals(5, steps.get(7).level());
        assertEquals(5, steps.get(8).level());
        // SETTLEMENT 经正向边(费用—汇总入→结算)到达——纯"反向谁依赖我"到不了它
        PropagationStep settle = steps.get(8);
        assertEquals("FEE_DETAIL —汇总入→ SETTLEMENT", settle.evidence().get("relationChain"));
    }

    @Test
    void keyFamilyBridgesItemCodeDrift() {
        engine.propagate(catalog(), "DRUG_STOCK", keyFamily(), fakeDb(), startKeys());
        // MEDICAL_ORDER 按「项目编码」列(item_code)查 D006——口径漂移经键族归一
        Executed mo = executed.stream().filter(e -> e.sql().contains("medical_order")).findFirst().orElseThrow();
        assertEquals("SELECT * FROM medical_order WHERE item_code IN (?) LIMIT 50", mo.sql());
        assertEquals(List.of("D006"), mo.params());
    }

    @Test
    void foreignKeyRelayCollectsOrderAndVisit() {
        engine.propagate(catalog(), "DRUG_STOCK", keyFamily(), fakeDb(), startKeys());
        // 发药行收割 医嘱号 ORD001/住院号 ZY001 → INP_VISIT 按住院号查其主表 inpatient
        // (主表选择:ownColCount 多者优先——inpatient 上 4 条 INP_VISIT 列,胜过 dispense_record 上 1 条跨引用)
        Executed disp = executed.stream().filter(e -> e.sql().contains("dispense_record")).findFirst().orElseThrow();
        assertEquals(List.of("D006"), disp.params());
        Executed visit = executed.stream().filter(e -> e.sql().contains("FROM inpatient")).findFirst().orElseThrow();
        assertEquals("SELECT * FROM inpatient WHERE visit_no IN (?) LIMIT 50", visit.sql());
        assertEquals(List.of("ZY001"), visit.params());
    }

    @Test
    void blockedGapStopsChainHonestly() {
        List<PropagationStep> steps = engine.propagate(catalog(), "DRUG_STOCK", keyFamily(), fakeDb(), startKeys());
        PropagationStep stockIn = steps.stream().filter(s -> "STOCK_IN".equals(s.concept())).findFirst().orElseThrow();
        assertEquals(PropagationStep.BLOCKED_GAP, stockIn.status());
        assertTrue(stockIn.evidence().get("note").toString().contains("没有可接力的"));
        // 断链确证:STOCK_IN 下游采购单(验收入库边)不入镜——BLOCKED_GAP 不扩展的回归防线
        assertTrue(steps.stream().noneMatch(s -> "DRUG_PURCHASE".equals(s.concept())));
    }

    @Test
    void evidenceCarriesRelationChainAndMapping() {
        List<PropagationStep> steps = engine.propagate(catalog(), "DRUG_STOCK", keyFamily(), fakeDb(), startKeys());
        PropagationStep stockOut = steps.stream().filter(s -> "STOCK_OUT".equals(s.concept())).findFirst().orElseThrow();
        assertEquals("STOCK_OUT —扣减库存→ DRUG_STOCK", stockOut.evidence().get("relationChain"));
        assertTrue(stockOut.evidence().get("mappingRefs").toString().contains("STOCK_OUT.drug_code → stock_out.drug_code"));
        assertEquals(1, stockOut.hits());
        assertEquals("D006", stockOut.sample().get(0).get("drug_code"));
    }

    @Test
    void emptyMappingContinuesViaOtherKeys() {
        StepQueryExecutor noDispense = (sql, params) -> {
            if (sql.contains("dispense_record")) {
                return List.of();
            }
            return fakeDb().query(sql, params);
        };
        List<PropagationStep> steps = engine.propagate(catalog(), "DRUG_STOCK", keyFamily(), noDispense, startKeys());
        PropagationStep disp = steps.stream().filter(s -> "DISPENSE".equals(s.concept())).findFirst().orElseThrow();
        assertEquals(PropagationStep.EMPTY, disp.status());
        // MEDICAL_ORDER 不经发药接力,仍按「项目编码」查到 D006 医嘱
        PropagationStep mo = steps.stream().filter(s -> "MEDICAL_ORDER".equals(s.concept())).findFirst().orElseThrow();
        assertEquals(PropagationStep.MATCHED, mo.status());
    }

    @Test
    void truncationDisciplineEnforced() {
        StepQueryExecutor wide = (sql, params) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("drug_code", "X".repeat(300));
            return List.of(r);
        };
        List<PropagationStep> steps = engine.propagate(catalog(), "DRUG_STOCK", keyFamily(), wide, startKeys());
        PropagationStep first = steps.get(0);
        assertTrue(first.sqlSummary().length() <= 200);
        assertEquals(120 + 1, ((String) first.sample().get(0).get("drug_code")).length()); // 120+省略号
    }
}
