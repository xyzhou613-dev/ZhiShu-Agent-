package com.bemodel.flow;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/flow")
@RequiredArgsConstructor
public class FlowController {

    private final FlowService flowService;

    @GetMapping("/patients")
    public Result<PageResult<Map<String, Object>>> patients(@RequestParam(required = false) String keyword,
                                                            @RequestParam(required = false) Integer pageNum,
                                                            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(flowService.patients(keyword,
                PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }

    @GetMapping("/loop/{inhosNo}")
    public Result<Map<String, Object>> loop(@PathVariable String inhosNo) {
        return Result.ok(flowService.loop(inhosNo));
    }

    @GetMapping("/opd/patients")
    public Result<PageResult<Map<String, Object>>> opdPatients(@RequestParam(required = false) String keyword,
                                                               @RequestParam(required = false) Integer pageNum,
                                                               @RequestParam(required = false) Integer pageSize) {
        return Result.ok(flowService.opdPatients(keyword,
                PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }

    @GetMapping("/opd/loop/{cardNo}")
    public Result<Map<String, Object>> opdLoop(@PathVariable String cardNo) {
        return Result.ok(flowService.opdLoop(cardNo));
    }

    @GetMapping("/staff")
    public Result<Map<String, Object>> staff(@RequestParam String name) {
        return Result.ok(flowService.staffDetail(name));
    }
}
