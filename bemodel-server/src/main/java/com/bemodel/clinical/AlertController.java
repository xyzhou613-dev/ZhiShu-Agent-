package com.bemodel.clinical;

import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/alert")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @PostMapping("/detect")
    public Result<Map<String, Object>> detect() {
        return Result.ok(alertService.detect());
    }
}
