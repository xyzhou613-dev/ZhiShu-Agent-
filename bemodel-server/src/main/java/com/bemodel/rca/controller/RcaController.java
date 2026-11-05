package com.bemodel.rca.controller;

import com.bemodel.common.Result;
import com.bemodel.rca.entity.RcaCase;
import com.bemodel.rca.service.RcaEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rca")
@RequiredArgsConstructor
public class RcaController {

    private final RcaEngine rcaEngine;

    @PostMapping("/start")
    public Result<RcaCase> start(@RequestParam String ticketRef) {
        return Result.ok(rcaEngine.start(ticketRef));
    }

    @GetMapping("/list")
    public Result<List<RcaCase>> list() {
        return Result.ok(rcaEngine.listCases());
    }

    @GetMapping("/{caseId}")
    public Result<Map<String, Object>> detail(@PathVariable Long caseId) {
        return Result.ok(rcaEngine.caseDetail(caseId));
    }
}
