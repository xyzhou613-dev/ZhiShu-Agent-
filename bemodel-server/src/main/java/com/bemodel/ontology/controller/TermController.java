package com.bemodel.ontology.controller;

import com.bemodel.common.Result;
import com.bemodel.ontology.entity.Term;
import com.bemodel.ontology.service.TermService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/term")
@RequiredArgsConstructor
public class TermController {

    private final TermService termService;

    @GetMapping("/list")
    public Result<List<Term>> list(@RequestParam(required = false) String conceptCode) {
        return Result.ok(termService.listAll(conceptCode));
    }

    @PostMapping
    public Result<Term> create(@RequestBody Term term) {
        termService.save(term);
        return Result.ok(term);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        termService.removeById(id);
        return Result.ok();
    }
}
