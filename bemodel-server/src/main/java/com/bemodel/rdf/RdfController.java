package com.bemodel.rdf;

import com.bemodel.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/rdf")
@RequiredArgsConstructor
public class RdfController {

    private final RdfService rdfService;
    private final ShaclService shaclService;

    /** 导出患者 ABox（Turtle），供处方审核原型加载。mask 默认 true：患者姓名脱敏（张*三） */
    @GetMapping("/patient/{inhosNo}")
    public ResponseEntity<String> exportPatient(@PathVariable String inhosNo,
                                                @RequestParam(defaultValue = "true") boolean mask) {
        String turtle = rdfService.exportPatient(inhosNo, mask);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "turtle", StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", inhosNo + ".ttl");
        return new ResponseEntity<>(turtle, headers, 200);
    }

    /** SHACL 真校验：导出患者 ABox → Jena SHACL 跑六 Shape，返回 conforms/violations */
    @PostMapping("/validate/{inhosNo}")
    public Result<Map<String, Object>> validatePatient(@PathVariable String inhosNo) {
        return Result.ok(shaclService.validatePatient(inhosNo));
    }
}
