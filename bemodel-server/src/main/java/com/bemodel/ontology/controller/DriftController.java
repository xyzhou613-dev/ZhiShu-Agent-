package com.bemodel.ontology.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bemodel.common.Result;
import com.bemodel.ontology.service.DriftService;

import lombok.RequiredArgsConstructor;

/**
 * 语义漂移检测接口：按需扫描，无副作用。
 */
@RestController
@RequestMapping("/api/drift")
@RequiredArgsConstructor
public class DriftController {

    private final DriftService driftService;

    @GetMapping("/scan")
    public Result<Map<String, Object>> scan() {
        return Result.ok(driftService.scan());
    }
}
