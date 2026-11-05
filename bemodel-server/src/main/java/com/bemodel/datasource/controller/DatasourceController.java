package com.bemodel.datasource.controller;

import com.bemodel.common.Result;
import com.bemodel.datasource.entity.Datasource;
import com.bemodel.datasource.entity.PhysicalColumn;
import com.bemodel.datasource.entity.PhysicalTable;
import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.datasource.service.SchemaScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasource")
@RequiredArgsConstructor
public class DatasourceController {

    private final DatasourceService datasourceService;
    private final SchemaScanService schemaScanService;

    @GetMapping("/list")
    public Result<List<Datasource>> list() {
        return Result.ok(datasourceService.listAll());
    }

    @PostMapping
    public Result<Datasource> create(@RequestBody Datasource ds) {
        return Result.ok(datasourceService.create(ds));
    }

    @PostMapping("/test")
    public Result<Boolean> test(@RequestBody Datasource ds) {
        return Result.ok(datasourceService.testConnection(ds));
    }

    @PostMapping("/scan/{dsCode}")
    public Result<Map<String, Object>> scan(@PathVariable String dsCode) {
        int count = schemaScanService.scan(dsCode);
        return Result.ok(Map.of("dsCode", dsCode, "tableCount", count));
    }

    @GetMapping("/tables/{dsCode}")
    public Result<List<PhysicalTable>> tables(@PathVariable String dsCode) {
        return Result.ok(schemaScanService.tables(dsCode));
    }

    @GetMapping("/columns/{dsCode}")
    public Result<List<PhysicalColumn>> columns(@PathVariable String dsCode,
                                                @RequestParam(required = false) String tableName) {
        return Result.ok(schemaScanService.columns(dsCode, tableName));
    }
}
