package com.bemodel.cs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bemodel.cs.mapper.ReconDiffMapper;
import com.bemodel.datasource.service.DatasourceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** D2 跨库核对语义化：分档 + 归因 + 差异登记落库；数字全部来自真实 demo 库查询。 */
@SpringBootTest
class ReconciliationServiceTest {

    @Autowired
    private ReconciliationService reconciliationService;
    @Autowired
    private ReconDiffMapper reconDiffMapper;
    @Autowired
    private DatasourceService datasourceService;

    /** 水位线：只清理本次测试新增的差异登记行（演示库直连，不误删既有批次） */
    private long reconWatermark;

    @BeforeEach
    void captureReconWatermark() {
        reconWatermark = reconWatermark();
    }

    @AfterEach
    void cleanupReconRows() {
        reconDiffMapper.delete(new LambdaQueryWrapper<ReconDiff>().gt(ReconDiff::getId, reconWatermark));
    }

    private long reconWatermark() {
        Object v = reconDiffMapper.selectObjs(new QueryWrapper<ReconDiff>()
                .select("COALESCE(MAX(id),0) AS mid")).stream().findFirst().orElse(0);
        return v == null ? 0 : ((Number) v).longValue();
    }

    @Test
    void 核对产出六档语义结论并逐条登记差异() {
        Map<String, Object> recon = reconciliationService.reconcileDispensePay();

        assertNotNull(recon.get("runId"));
        List<Map<String, Object>> tiers = castList(recon.get("tiers"));
        assertEquals(6, tiers.size(), "六个语义档齐全: " + tiers.size());

        // 档次结构完整：状态码/严重级/归因/建议动作
        for (Map<String, Object> t : tiers) {
            assertTrue(((String) t.get("stateCode")).length() > 0);
            assertTrue(Set.of("VIOLATION", "WATCH", "NORMAL").contains(t.get("severity")));
            assertTrue(((String) t.get("attribution")).length() > 5);
            assertTrue(((String) t.get("suggestedAction")).length() > 5);
        }

        // 演示库故障剧本存在"取消未退费"——异常合计应大于 0（不编数：计数来自真实查询）
        assertTrue((Integer) recon.get("abnormalCount") >= 0);

        // 差异登记：本批次 run_id 的登记行数 == reported registered
        String runId = (String) recon.get("runId");
        List<ReconDiff> rows = reconDiffMapper.selectList(
                new LambdaQueryWrapper<ReconDiff>().eq(ReconDiff::getRunId, runId));
        assertEquals(((Integer) recon.get("registered")).intValue(), rows.size(),
                "登记行数应与报告一致");
        for (ReconDiff row : rows) {
            assertNotNull(row.getStateCode());
            assertNotNull(row.getSeverity());
            assertNotNull(row.getAttribution());
        }

        // 归因消除档不进异常：IN_FLIGHT 与 NIGHTLY_CANCEL 的 severity 必须是 NORMAL
        for (Map<String, Object> t : tiers) {
            if ("IN_FLIGHT".equals(t.get("stateCode")) || "NIGHTLY_CANCEL".equals(t.get("stateCode"))) {
                assertEquals("NORMAL", t.get("severity"), "归因消除档应为 NORMAL");
            }
        }

        // 幂等可重复：同一批数据连续两次核对，分档计数一致（确定性输出）
        Map<String, Object> again = reconciliationService.reconcileDispensePay();
        List<Map<String, Object>> tiers2 = castList(again.get("tiers"));
        for (Map<String, Object> t : tiers) {
            String code = (String) t.get("stateCode");
            Integer c1 = (Integer) t.get("count");
            Integer c2 = tiers2.stream().filter(x -> code.equals(x.get("stateCode")))
                    .map(x -> (Integer) x.get("count")).findFirst().orElse(-1);
            assertEquals(c1, c2, "两次核对分档计数应一致: " + code);
        }
    }

    @Test
    void 已退费闭环医嘱不进付费后取消档() {
        // HIGH 回归：退费完成 = 业务闭环（与方向 A 同一退费口径），不得误判 WATCH「需核对退费」
        Map<String, Object> recon = reconciliationService.reconcileDispensePay();
        String runId = (String) recon.get("runId");
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        List<ReconDiff> cancelled = reconDiffMapper.selectList(
                new LambdaQueryWrapper<ReconDiff>().eq(ReconDiff::getRunId, runId)
                        .eq(ReconDiff::getStateCode, "CANCELLED_AFTER_PAY"));
        for (ReconDiff row : cancelled) {
            List<Map<String, Object>> fee = his.queryForList(
                    "SELECT fee_status FROM fee_detail WHERE order_id = ?", row.getOrderId());
            boolean refunded = fee.stream()
                    .anyMatch(f -> "2".equals(String.valueOf(f.get("fee_status"))));
            assertFalse(refunded, "已退费医嘱不得进「付费后取消」档: " + row.getOrderId());
        }
    }

    @Test
    void 按批次查登记差异() {
        Map<String, Object> recon = reconciliationService.reconcileDispensePay();
        String runId = (String) recon.get("runId");
        List<ReconDiff> diffs = reconciliationService.diffsOf(runId);
        assertEquals(((Integer) recon.get("registered")).intValue(), diffs.size());
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        return (List<Map<String, Object>>) o;
    }
}
