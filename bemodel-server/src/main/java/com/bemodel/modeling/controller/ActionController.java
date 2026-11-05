package com.bemodel.modeling.controller;

import com.bemodel.common.Result;
import com.bemodel.modeling.entity.Action;
import com.bemodel.modeling.service.ActionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/action")
@RequiredArgsConstructor
public class ActionController {

    private final ActionService actionService;

    @GetMapping("/list")
    public Result<List<Action>> list(@RequestParam(required = false) String conceptCode) {
        return Result.ok(actionService.list(conceptCode));
    }

    @PostMapping
    public Result<Action> create(@RequestBody Action action) {
        return Result.ok(actionService.create(action));
    }

    @PutMapping
    public Result<Action> update(@RequestBody Action action) {
        actionService.updateById(action);
        return Result.ok(action);
    }

    @PostMapping("/transition/{actionCode}")
    public Result<Action> transition(@PathVariable String actionCode, @RequestParam String target) {
        return Result.ok(actionService.transition(actionCode, target));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        actionService.removeById(id);
        return Result.ok();
    }
}
