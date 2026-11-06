package com.bemodel.compute;

import com.bemodel.common.Result;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@Profile("compute")
@RequestMapping("/api/compute")
final class ComputeController {
    record GroupOverview(String nodeKey, Instant evidenceAsOf, List<ComputeGroupQuery.Group> groups) {}

    private final ComputeGroupQuery query;

    ComputeController(ComputeGroupQuery query) {
        this.query = query;
    }

    @GetMapping("/groups")
    Result<GroupOverview> groups(@RequestParam(defaultValue = "a800-demo") String nodeKey,
                                 @RequestParam(required = false) Instant evidenceAsOf) {
        Instant at = evidenceAsOf == null ? Instant.now() : evidenceAsOf;
        return Result.ok(new GroupOverview(nodeKey, at, query.groups(nodeKey, at)));
    }
}
