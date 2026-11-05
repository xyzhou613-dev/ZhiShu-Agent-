package com.bemodel.impact;

import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/impact")
@RequiredArgsConstructor
public class ImpactController {

    private final ImpactService impactService;

    @PostMapping("/analyze")
    public Result<Map<String, Object>> analyze(@RequestBody Map<String, String> body) {
        String depthStr = body.get("depth");
        Integer depth = null;
        if (depthStr != null && !depthStr.isBlank()) {
            depth = Integer.valueOf(depthStr);
        }
        return Result.ok(impactService.analyze(body.get("conceptCode"), body.get("changeDesc"), depth));
    }
}
