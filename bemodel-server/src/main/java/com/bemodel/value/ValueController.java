package com.bemodel.value;

import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/value")
@RequiredArgsConstructor
public class ValueController {

    private final ValueService valueService;

    /** 价值实证：同一业务问题，A(AI+本体)/B(AI+裸SQL)/C(传统系统) 能力边界对比 */
    @GetMapping("/compare")
    public Result<Map<String, Object>> compare() {
        return Result.ok(valueService.compare());
    }
}
