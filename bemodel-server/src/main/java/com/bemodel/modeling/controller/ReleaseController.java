package com.bemodel.modeling.controller;

import com.bemodel.common.Result;
import com.bemodel.modeling.entity.Release;
import com.bemodel.modeling.service.ReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/release")
@RequiredArgsConstructor
public class ReleaseController {

    private final ReleaseService releaseService;

    @GetMapping("/list")
    public Result<List<Release>> list() {
        return Result.ok(releaseService.listAll());
    }

    @GetMapping("/current")
    public Result<Map<String, String>> current() {
        String tag = releaseService.currentTag();
        return Result.ok(Map.of("version", tag == null ? "未发布" : tag));
    }

    @PostMapping("/publish")
    public Result<Release> publish(@RequestBody Map<String, String> body) {
        boolean force = "true".equalsIgnoreCase(body.get("force"));
        return Result.ok(releaseService.publish(body.get("changeSummary"), body.get("releasedBy"), force));
    }

    @GetMapping("/{id}")
    public Result<Release> detail(@PathVariable Long id) {
        return Result.ok(releaseService.getById(id));
    }
}
