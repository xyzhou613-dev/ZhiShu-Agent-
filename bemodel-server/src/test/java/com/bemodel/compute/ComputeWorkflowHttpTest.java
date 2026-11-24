package com.bemodel.compute;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@ActiveProfiles("compute") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="COMPUTE_MYSQL_SMOKE",matches="true")
@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeWorkflowHttpTest {
    static ComputeMysqlTestDatabase db;
    @MockBean ComputeDatasourceBootstrap bootstrap;
    @Autowired TestRestTemplate http;@Autowired ComputeReleaseBridge bridge;@Autowired ComputeDatabase.Store store;@Autowired ObjectMapper json;
    @Autowired @Qualifier("platformJdbcTemplate") JdbcTemplate platform;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p)throws Exception{
        db=ComputeMysqlTestDatabase.create();p.add("spring.datasource.url",db::platformUrl);p.add("spring.datasource.username",ComputeMysqlTestDatabase::username);p.add("spring.datasource.password",ComputeMysqlTestDatabase::password);p.add("compute.datasource.jdbc-url",db::businessUrl);p.add("compute.datasource.username",ComputeMysqlTestDatabase::username);p.add("compute.datasource.password",ComputeMysqlTestDatabase::password);p.add("spring.flyway.locations",()->"classpath:db/compute-platform");p.add("spring.config.import",()->"");p.add("COMPUTE_ADMIN_PASSWORD",()->"workflow-test-admin-password");p.add("jwt.secret",()->"workflow-test-jwt-secret-at-least-32-bytes");p.add("app.secret-key",()->"workflow-test-encryption-key");p.add("compute.simulator.enabled",()->"true");p.add("compute.workflow.enabled",()->"false");p.add("compute.runtime.mode",()->"SIMULATION");p.add("compute.queue.recheck-enabled",()->"false");p.add("bemodel.inspect.cron",()->"-");p.add("bemodel.reconcile.cron",()->"-");p.add("bemodel.proposal.cron",()->"-");
    }
    @AfterAll static void cleanup()throws Exception{if(db!=null)db.close();}
    JsonNode call(String path,Object body,HttpHeaders h){var response=body==null?http.exchange(path,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class):http.exchange(path,HttpMethod.POST,new HttpEntity<>(body,h),JsonNode.class);assertEquals(200,response.getStatusCode().value());var result=response.getBody();assertEquals(0,result.path("code").asInt(-1),result.toString());return result.path("data");}
    @Test void runsSingleCardLifecycleThroughAuthenticatedHttp()throws Exception{
        platform.update("UPDATE bm_compute_data_asset SET authoritative_source='A800_SIMULATOR_V1',source_kind='SIMULATED',max_age_seconds=120,version=version+1 WHERE asset_code IN ('GPU_OBSERVATION','NODE_WATERMARK')");
        var rules=ComputeReleaseBridgeTest.rules();for(var rule:rules)if("R-11".equals(rule.getRuleCode())){var expr=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(rule.getExprJson());((com.fasterxml.jackson.databind.node.ObjectNode)expr.path("parameters")).put("maxAgeSeconds",120);rule.setExprJson(expr.toString());}
        var config=bridge.prepare(rules);new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(platform.getDataSource())).executeWithoutResult(tx->{bridge.lockPublication();try{String payload=json.writeValueAsString(Map.of("compute",config));long id=ComputeReleaseBridge.insert(platform,"INSERT INTO bm_release(version_tag,snapshot_json) VALUES('workflow-test',?)",payload);var r=new com.bemodel.modeling.entity.Release();r.setId(id);r.setVersionTag("workflow-test");r.setSnapshotJson(payload);bridge.stage(r);}catch(Exception e){throw new RuntimeException(e);}});
        var login=http.postForObject("/api/auth/login",Map.of("username","admin","password","workflow-test-admin-password"),JsonNode.class);var h=new HttpHeaders();h.setBearerAuth(login.path("data").path("token").asText());call("/api/compute/simulator/nodes/a800-demo/initialize",Map.of(),h);call("/api/compute/simulator/nodes/a800-demo/observations",Map.of("validForSeconds",120),h);
        var body=Map.of("employeeId",1,"projectId",1,"taskTypeId",1,"clientRequestKey",UUID.randomUUID().toString(),"requestedGpuCount",1,"requestedResourceSpec",Map.of("memoryBytes",1024,"vramBytesPerGpu",1073741824,"gpuInterconnect","NONE"),"launchSpec",Map.of("imageRef","demo/train"));long request=call("/api/compute/requests",body,h).path("id").asLong();var selection=Map.of("nodeKey","a800-demo","requestId",request);call("/api/compute/queue",selection,h);var reserved=call("/api/compute/reservations",selection,h);long id=call("/api/compute/workflows",Map.of("reservationReceiptId",reserved.path("receipt").path("id").asLong()),h).path("id").asLong();
        call("/api/compute/workflows/"+id+"/advance",Map.of(),h);assertEquals("RUNNING",call("/api/compute/workflows/"+id+"/advance",Map.of(),h).path("step").asText());call("/api/compute/workflows/"+id+"/stop",Map.of(),h);for(int i=0;i<3;i++)call("/api/compute/workflows/"+id+"/advance",Map.of(),h);assertEquals("DONE",call("/api/compute/workflows/"+id,null,h).path("step").asText());
        assertEquals(401,http.getForEntity("/api/compute/workflows",JsonNode.class).getStatusCode().value());
        if("true".equals(System.getenv("COMPUTE_BROWSER_DEMO"))){
            var ready=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"workflow-browser-ready.txt");var done=ready.resolveSibling("workflow-browser-done.txt");java.nio.file.Files.deleteIfExists(done);java.nio.file.Files.writeString(ready,http.getRootUri());long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(8);while(!java.nio.file.Files.exists(done)&&System.nanoTime()<deadline)Thread.sleep(500);assertTrue(java.nio.file.Files.exists(done),"浏览器验收未完成");java.nio.file.Files.deleteIfExists(ready);
        }
    }
}
