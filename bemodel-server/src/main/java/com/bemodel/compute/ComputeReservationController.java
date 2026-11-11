package com.bemodel.compute;
import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("compute") @RequestMapping("/api/compute/reservations")
final class ComputeReservationController {
    private final ComputeReservationService reservations;
    ComputeReservationController(ComputeReservationService reservations){this.reservations=reservations;}
    @PostMapping Result<?> reserve(@RequestBody JsonNode body){return Result.ok(reservations.reserve(body));}
    @GetMapping Result<?> list(@RequestParam(required=false)Integer pageNum,@RequestParam(required=false)Integer pageSize,@RequestParam(required=false)Long requestId){return Result.ok(reservations.list(PageResult.pageNum(pageNum),PageResult.pageSize(pageSize,20),requestId));}
    @GetMapping("/{id}")Result<?> detail(@PathVariable long id){return Result.ok(reservations.detail(id));}
}
