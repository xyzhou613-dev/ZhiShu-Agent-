package com.bemodel.ontology.controller;

import com.bemodel.common.Result;
import com.bemodel.ontology.entity.Domain;
import com.bemodel.ontology.service.DomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/domain")
@RequiredArgsConstructor
public class DomainController {

    private final DomainService domainService;

    @GetMapping("/list")
    public Result<List<Domain>> list() {
        return Result.ok(domainService.listAll());
    }

    @PostMapping
    public Result<Domain> create(@RequestBody Domain domain) {
        domainService.save(domain);
        return Result.ok(domain);
    }
}
