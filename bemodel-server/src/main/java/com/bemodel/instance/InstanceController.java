package com.bemodel.instance;

import com.bemodel.common.PageResult;
import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/instance")
@RequiredArgsConstructor
public class InstanceController {

    private final InstanceService instanceService;

    @GetMapping("/{conceptCode}")
    public Result<Map<String, Object>> instances(@PathVariable String conceptCode,
                                                 @RequestParam(required = false) String tableName,
                                                 @RequestParam(required = false) Integer pageNum,
                                                 @RequestParam(required = false) Integer pageSize) {
        return Result.ok(instanceService.instances(conceptCode, tableName,
                PageResult.pageNum(pageNum), PageResult.pageSize(pageSize, 20)));
    }
}
