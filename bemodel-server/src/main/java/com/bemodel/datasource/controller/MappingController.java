package com.bemodel.datasource.controller;

import com.bemodel.common.Result;
import com.bemodel.datasource.entity.Mapping;
import com.bemodel.datasource.service.MappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mapping")
@RequiredArgsConstructor
public class MappingController {

    private final MappingService mappingService;

    @GetMapping("/list")
    public Result<List<Mapping>> list(@RequestParam(required = false) String dsCode,
                                      @RequestParam(required = false) String tableName,
                                      @RequestParam(required = false) String status) {
        return Result.ok(mappingService.list(dsCode, tableName, status));
    }

    @PostMapping("/batch")
    public Result<Void> saveBatch(@RequestBody List<Mapping> mappings) {
        mappingService.saveBatch(mappings);
        return Result.ok();
    }

    @GetMapping("/ai-suggest")
    public Result<Map<String, Object>> aiSuggest(@RequestParam String dsCode,
                                                 @RequestParam String tableName) {
        return Result.ok(mappingService.aiSuggest(dsCode, tableName));
    }

    /** 编辑映射语义（概念/属性/值字典），带前后对照留痕 */
    @PutMapping("/{id}")
    public Result<Mapping> update(@PathVariable Long id, @RequestBody Mapping patch) {
        return Result.ok(mappingService.updateMapping(id, patch));
    }

    /** 生命周期流转（V30）：PROPOSED→ACTIVE→DEPRECATED（可重新启用），需 EDITOR/ADMIN */
    @PostMapping("/{id}/transition")
    public Result<Mapping> transition(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.ok(mappingService.transition(id, body.get("target")));
    }

    /** 变更留痕查询：CREATE/UPDATE/TRANSITION/DELETE 前后对照 */
    @GetMapping("/{id}/log")
    public Result<List<com.bemodel.datasource.entity.MappingLog>> log(@PathVariable Long id) {
        return Result.ok(mappingService.logOf(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        mappingService.deleteLogged(id);
        return Result.ok();
    }
}
