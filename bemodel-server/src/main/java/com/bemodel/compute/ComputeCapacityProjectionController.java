package com.bemodel.compute;
import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("compute") @RequestMapping("/api/compute/capacity-projections")
final class ComputeCapacityProjectionController {
    private final ComputeCapacityProjectionService projections;
    ComputeCapacityProjectionController(ComputeCapacityProjectionService projections){this.projections=projections;}
    @PostMapping Result<ComputeCapacityProjectionService.Detail> project(@RequestBody JsonNode body){return Result.ok(projections.project(body));}
    @GetMapping Result<PageResult<ComputeCapacityProjectionService.Run>> list(@RequestParam(required=false) Integer pageNum,@RequestParam(required=false) Integer pageSize,@RequestParam(required=false) Long requestId){return Result.ok(projections.list(PageResult.pageNum(pageNum),PageResult.pageSize(pageSize,20),requestId));}
    @GetMapping("/{id}") Result<ComputeCapacityProjectionService.Detail> detail(@PathVariable long id){return Result.ok(projections.detail(id));}
}
