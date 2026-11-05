package com.bemodel.architecture;

import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/architecture")
@RequiredArgsConstructor
public class ArchitectureController {

    private final ArchitectureService architectureService;

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(architectureService.overview());
    }
}
