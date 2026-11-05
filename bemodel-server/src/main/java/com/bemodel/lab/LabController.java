package com.bemodel.lab;

import com.bemodel.common.Result;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 对比实验室 API:实验清单/起跑/轮询/最近运行/沙箱重置/审计探针。
 * 前端(Plan 3 /lab 页)流程:experiments 选实验 → run 起跑 → 轮询 run/{id} → 展示三臂卡+锚点行+轨迹展开 → audit 面板。
 */
@RestController
@RequestMapping("/api/lab")
@RequiredArgsConstructor
public class LabController {

    private final LabRunService labRunService;

    /** 实验清单(前端实验选择器数据源) */
    @GetMapping("/experiments")
    public Result<List<Map<String, Object>>> experiments() {
        return Result.ok(labRunService.experiments());
    }

    /** 起跑一个实验的三臂对照(异步,返回 runId 轮询) */
    @PostMapping("/run")
    public Result<Map<String, Object>> start(@RequestBody LabRunReq req) {
        return Result.ok(labRunService.start(req.getExperimentKey(), req.getQuestion(), req.getForceExecute()));
    }

    /** 轮询单次运行:状态 + 三臂完整结果 */
    @GetMapping("/run/{id}")
    public Result<Map<String, Object>> status(@PathVariable Long id) {
        return Result.ok(labRunService.status(id));
    }

    /** 最近运行列表 */
    @GetMapping("/runs")
    public Result<List<Map<String, Object>>> recent() {
        return Result.ok(labRunService.recent());
    }

    /** 沙箱重置(恢复种子状态) */
    @PostMapping("/reset")
    public Result<Map<String, Object>> reset() {
        return Result.ok(labRunService.reset());
    }

    /** 沙箱审计探针(禁忌医嘱/重复退费/账本实存) */
    @GetMapping("/audit")
    public Result<Map<String, Object>> audit() {
        return Result.ok(labRunService.audit());
    }

    @Data
    public static class LabRunReq {
        private String experimentKey;
        private String question;
        private Boolean forceExecute;
    }
}
