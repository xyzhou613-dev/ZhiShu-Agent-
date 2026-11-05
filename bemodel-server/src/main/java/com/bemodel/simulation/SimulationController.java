package com.bemodel.simulation;

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
 * 推演沙盘 API:场景清单/起跑/轮询/最近推演/恢复演示数据。
 * 前端(/simulation)流程:scenarios 选场景 → run 起跑 → 轮询 run/{id} → 时间轴分层点亮 → diff 卡 → reset 恢复。
 */
@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationService simulationService;

    /** 场景清单(含参数默认值) */
    @GetMapping("/scenarios")
    public Result<List<Map<String, Object>>> scenarios() {
        return Result.ok(simulationService.scenarios());
    }

    /** 起跑一次推演(异步,返回 runId 轮询) */
    @PostMapping("/run")
    public Result<Map<String, Object>> start(@RequestBody SimulationRunReq req) {
        return Result.ok(simulationService.start(req.getScenario(), req.getParams()));
    }

    /** 轮询单次推演:状态 + 完整 payload(波及步骤/观察/diff) */
    @GetMapping("/run/{id}")
    public Result<Map<String, Object>> status(@PathVariable Long id) {
        return Result.ok(simulationService.status(id));
    }

    /** 最近推演列表(旧 run 回看:再调 run/{id} 即得完整 payload) */
    @GetMapping("/runs")
    public Result<List<Map<String, Object>>> recent() {
        return Result.ok(simulationService.recent());
    }

    /** 恢复演示数据(沙箱整体重克隆) */
    @PostMapping("/reset")
    public Result<Map<String, Object>> reset() {
        return Result.ok(simulationService.reset());
    }

    @Data
    public static class SimulationRunReq {
        private String scenario;
        private Map<String, String> params;
    }
}
