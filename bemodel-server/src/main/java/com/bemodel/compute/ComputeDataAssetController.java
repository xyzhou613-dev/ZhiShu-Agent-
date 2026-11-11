package com.bemodel.compute;
import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
@RestController @Profile("compute") @RequestMapping("/api/compute")
final class ComputeDataAssetController {
    private final ComputeDataAssetService assets;
    ComputeDataAssetController(ComputeDataAssetService assets){this.assets=assets;}
    @GetMapping("/data-assets") Result<?> list(){return Result.ok(assets.list(false));}
    @GetMapping("/data-assets/{code}") Result<?> detail(@PathVariable String code){return Result.ok(assets.require(code));}
    @PutMapping("/data-assets/{code}") Result<?> update(@PathVariable String code,@RequestBody JsonNode body){return Result.ok(assets.update(code,body));}
    @GetMapping("/data-contract") Result<?> contract(){return Result.ok(assets.collectionContract());}
}
