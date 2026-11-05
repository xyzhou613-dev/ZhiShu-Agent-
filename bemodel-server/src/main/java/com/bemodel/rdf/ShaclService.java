package com.bemodel.rdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.validation.ReportEntry;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SHACL 真校验（Jena SHACL）：把 RdfService 导出的患者 ABox（Turtle）
 * 与 clinical-shapes.ttl（六 Shape，与顶层模板一一对应）跑 sh:validate。
 * 与平台 QC 引擎（bm_rule + expr_json 探针）同一语义的双引擎互证。LLM 不参与。
 */
@Slf4j
@Service
public class ShaclService {

    private final RdfService rdfService;
    private volatile Graph shapesGraph;

    public ShaclService(RdfService rdfService) {
        this.rdfService = rdfService;
    }

    public Map<String, Object> validatePatient(String inhosNo) {
        return validateTurtle(rdfService.exportPatient(inhosNo), inhosNo);
    }

    /** 纯校验入口：对任意患者 ABox Turtle 跑六 Shape（不依赖数据库，便于单测复用） */
    public Map<String, Object> validateTurtle(String turtle, String patientId) {
        Model data = ModelFactory.createDefaultModel();
        RDFParser.fromString(turtle).lang(Lang.TURTLE).parse(data.getGraph());

        ValidationReport report = ShaclValidator.get().validate(loadShapes(), data.getGraph());
        List<Map<String, Object>> violations = new ArrayList<>();
        for (ReportEntry e : report.getEntries()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("focusNode", String.valueOf(e.focusNode()));
            v.put("path", e.resultPath() == null ? "" : String.valueOf(e.resultPath()));
            v.put("message", e.message());
            v.put("severity", "Violation"); // SHACL 未自定义 severity，默认即 sh:Violation
            violations.add(v);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patientId", patientId);
        result.put("conforms", report.conforms());
        result.put("violations", violations);
        result.put("violationCount", violations.size());
        result.put("shapes", "shacl/clinical-shapes.ttl（6 Shapes）");
        result.put("engine", "Apache Jena SHACL 5.2.0");
        return result;
    }

    /** Shapes 图只加载一次（classpath 资源不可变） */
    private Graph loadShapes() {
        if (shapesGraph == null) {
            synchronized (this) {
                if (shapesGraph == null) {
                    try (InputStream in = getClass().getResourceAsStream("/shacl/clinical-shapes.ttl")) {
                        if (in == null) {
                            throw new IllegalStateException("shacl/clinical-shapes.ttl 不在 classpath");
                        }
                        Model shapes = ModelFactory.createDefaultModel();
                        RDFParser.source(in).lang(Lang.TURTLE).parse(shapes.getGraph());
                        shapesGraph = shapes.getGraph();
                    } catch (Exception e) {
                        throw new IllegalStateException("SHACL shapes 加载失败: " + e.getMessage(), e);
                    }
                }
            }
        }
        return shapesGraph;
    }
}
