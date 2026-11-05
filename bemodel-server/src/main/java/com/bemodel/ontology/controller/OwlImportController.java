package com.bemodel.ontology.controller;

import com.bemodel.common.Result;
import com.bemodel.ontology.service.OwlImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * OWL 导入：预览与落库同一计划（execute 内部重新解析同一文件再走同一代码路径落库）。
 */
@RestController
@RequestMapping("/api/ontology/import")
@RequiredArgsConstructor
public class OwlImportController {

    private final OwlImportService owlImportService;

    @PostMapping("/preview")
    public Result<Map<String, Object>> preview(@RequestParam("file") MultipartFile file) {
        return Result.ok(owlImportService.preview(file));
    }

    @PostMapping("/execute")
    public Result<Map<String, Object>> execute(@RequestParam("file") MultipartFile file) {
        return Result.ok(owlImportService.execute(file));
    }
}
