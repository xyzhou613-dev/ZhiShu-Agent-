package com.bemodel.link.controller;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import com.bemodel.link.entity.LinkNode;
import com.bemodel.link.entity.LinkRel;
import com.bemodel.link.service.LinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/link")
@RequiredArgsConstructor
public class LinkController {

    private final LinkService linkService;

    @GetMapping("/list")
    public Result<PageResult<LinkNode>> list(@RequestParam(required = false) String nodeType,
                                             @RequestParam(required = false) String conceptCode,
                                             @RequestParam(required = false) Integer pageNum,
                                             @RequestParam(required = false) Integer pageSize) {
        return Result.ok(linkService.listPage(nodeType, conceptCode,
                PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }

    @GetMapping("/chain/{conceptCode}")
    public Result<Map<String, List<LinkNode>>> chain(@PathVariable String conceptCode) {
        return Result.ok(linkService.chainByConcept(conceptCode));
    }

    @GetMapping("/chain/{conceptCode}/rels")
    public Result<List<LinkRel>> chainRels(@PathVariable String conceptCode) {
        return Result.ok(linkService.relsByConcept(conceptCode));
    }

    @GetMapping("/trace/{refNo}")
    public Result<Map<String, Object>> trace(@PathVariable String refNo) {
        return Result.ok(linkService.trace(refNo));
    }

    @PostMapping("/rel")
    public Result<LinkRel> createRel(@RequestBody LinkRel rel) {
        return Result.ok(linkService.addRel(rel.getFromRefNo(), rel.getToRefNo(), rel.getRelType(), rel.getRemark()));
    }

    @DeleteMapping("/rel/{id}")
    public Result<Void> deleteRel(@PathVariable Long id) {
        linkService.removeRel(id);
        return Result.ok(null);
    }

    @PostMapping("/auto-ticket")
    public Result<Map<String, Object>> autoTicket() {
        return Result.ok(linkService.autoTicket());
    }

    @PostMapping
    public Result<LinkNode> create(@RequestBody LinkNode node) {
        linkService.save(node);
        return Result.ok(node);
    }

    @PutMapping
    public Result<LinkNode> update(@RequestBody LinkNode node) {
        linkService.updateById(node);
        return Result.ok(node);
    }
}
