package com.bemodel.modeling.controller;

import com.bemodel.common.Result;
import com.bemodel.modeling.entity.Axiom;
import com.bemodel.modeling.service.AxiomService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/axiom")
@RequiredArgsConstructor
public class AxiomController {

    private final AxiomService axiomService;

    @GetMapping("/list")
    public Result<List<Axiom>> list() {
        return Result.ok(axiomService.listAll());
    }

    @PostMapping
    public Result<Axiom> create(@RequestBody Axiom axiom) {
        axiomService.save(axiom);
        return Result.ok(axiom);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        axiomService.removeById(id);
        return Result.ok();
    }
}
