package com.bemodel.notice;

import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/inspect")
@RequiredArgsConstructor
public class InspectController {

    private final InspectService inspectService;

    /** 手动触发一次全量指标巡检（与定时器同一逻辑） */
    @PostMapping("/run")
    public Result<Map<String, Object>> run() {
        return Result.ok(inspectService.runAll());
    }
}
