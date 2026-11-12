package com.bemodel.compute;

import com.bemodel.auth.JwtService;
import com.bemodel.auth.PlatformUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("compute")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="COMPUTE_MYSQL_SMOKE",matches="true")
@EnabledIfEnvironmentVariable(named="COMPUTE_ONTOLOGY_MYSQL_URL",matches="jdbc:mysql:.*")
class ComputeSimulatorHttpTest {
    static ComputeMysqlTestDatabase db;
    @MockBean ComputeDatasourceBootstrap bootstrap;
    @Autowired TestRestTemplate http;@Autowired JwtService jwt;@Autowired ComputeActionService actions;
    @Autowired ComputeDatabase.Store store;@Autowired ObjectMapper json;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p)throws Exception {
        db=ComputeMysqlTestDatabase.create();p.add("spring.datasource.url",db::platformUrl);p.add("spring.datasource.username",ComputeMysqlTestDatabase::username);p.add("spring.datasource.password",ComputeMysqlTestDatabase::password);
        p.add("compute.datasource.jdbc-url",db::businessUrl);p.add("compute.datasource.username",ComputeMysqlTestDatabase::username);p.add("compute.datasource.password",ComputeMysqlTestDatabase::password);
        p.add("spring.flyway.locations",()->"classpath:db/compute-platform");p.add("spring.config.import",()->"");p.add("COMPUTE_ADMIN_PASSWORD",()->"n13-isolated-test-admin");
        p.add("jwt.secret",()->"n13-isolated-jwt-secret-at-least-32-bytes");p.add("app.secret-key",()->"n13-isolated-test-encryption");p.add("compute.simulator.enabled",()->"true");p.add("compute.runtime.mode",()->"SIMULATION");p.add("compute.runtime.node-key",()->"a800-demo");p.add("compute.queue.recheck-enabled",()->"false");
        p.add("bemodel.inspect.cron",()->"-");p.add("bemodel.reconcile.cron",()->"-");p.add("bemodel.proposal.cron",()->"-");
    }
    @AfterAll static void cleanup()throws Exception{if(db!=null)db.close();}
    HttpHeaders headers(String role){var user=new PlatformUser();user.setUsername("test-"+role);user.setRole(role);var h=new HttpHeaders();h.setBearerAuth(jwt.issue(user));return h;}
    JsonNode call(HttpMethod method,String path,Object body,HttpHeaders headers){var r=http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);assertEquals(200,r.getStatusCode().value(),path);assertEquals(0,r.getBody().path("code").asInt(-1),r.getBody().toString());return r.getBody().path("data");}
    @Test void controlsSimulationOverHttpAndReconcilesALostStopReceiptUsingN12() {
        var login=http.postForObject("/api/auth/login",Map.of("username","admin","password","n13-isolated-test-admin"),JsonNode.class);assertEquals(0,login.path("code").asInt(-1));var admin=new HttpHeaders();admin.setBearerAuth(login.path("data").path("token").asText());
        assertTrue(call(HttpMethod.GET,"/api/compute/simulator",null,admin).path("simulation").asBoolean());assertTrue(call(HttpMethod.GET,"/api/compute/runtime",null,admin).path("simulatorEnabled").asBoolean());
        assertEquals(401,http.getForEntity("/api/compute/simulator",JsonNode.class).getStatusCode().value());
        for(String role:List.of("EDITOR","VIEWER","REVIEWER"))assertEquals(403,http.exchange("/api/compute/simulator/nodes/a800-demo/initialize",HttpMethod.POST,new HttpEntity<>(Map.of(),headers(role)),JsonNode.class).getStatusCode().value());
        assertEquals(403,http.exchange("/api/compute/simulator",HttpMethod.GET,new HttpEntity<>(headers("VIEWER")),JsonNode.class).getStatusCode().value());
        var state=call(HttpMethod.POST,"/api/compute/simulator/nodes/a800-demo/initialize",Map.of(),admin);assertEquals(8,state.path("gpus").size());assertEquals(3,state.path("deployments").size());
        assertEquals(8,call(HttpMethod.GET,"/api/compute/simulator/nodes/a800-demo",null,headers("EDITOR")).path("gpus").size());
        var observations=call(HttpMethod.POST,"/api/compute/simulator/nodes/a800-demo/observations",Map.of(),admin);assertEquals(9,observations.path("observationIds").size());assertEquals("A800_SIMULATOR_V1",observations.path("source").asText());
        call(HttpMethod.PUT,"/api/compute/simulator/nodes/a800-demo/state",Map.of("deployments",List.of(Map.of("id",2,"inFlightRequests",0))),admin);
        var j=store.jdbc();long snapshot=ComputeReleaseBridge.insert(j,"INSERT INTO evidence_snapshot(as_of) VALUES(UTC_TIMESTAMP(6))");long decision=ComputeReleaseBridge.insert(j,"INSERT INTO decision(request_id,kind,outcome,as_of,snapshot_id) SELECT 1,'SUBMISSION','APPROVED',as_of,id FROM evidence_snapshot WHERE id=?",snapshot);
        long flow=ComputeReleaseBridge.insert(j,"INSERT INTO workflow_run(request_id,decision_id,group_id,job_id,restore_revision_id) VALUES(1,?,2,1,2)",decision);
        var drain=actions.prepare(new ComputeActionService.Intent(flow,"DRAIN","DEPLOYMENT",2,"http-drain",json.createObjectNode()));assertEquals("SUCCEEDED",actions.dispatch(drain.id()).status());
        call(HttpMethod.PUT,"/api/compute/simulator/scenarios",Map.of("operationKey","http-stop","mode","TIMEOUT"),admin);
        var stop=actions.prepare(new ComputeActionService.Intent(flow,"STOP","DEPLOYMENT",2,"http-stop",json.createObjectNode()));assertEquals("UNKNOWN",actions.dispatch(stop.id()).status());
        assertEquals(500,http.exchange("/api/compute/execution-actions/"+stop.id()+"/retry",HttpMethod.POST,new HttpEntity<>(Map.of(),admin),JsonNode.class).getBody().path("code").asInt());
        assertEquals("SUCCEEDED",call(HttpMethod.POST,"/api/compute/execution-actions/"+stop.id()+"/reconcile",Map.of(),admin).path("status").asText());
        var operation=call(HttpMethod.GET,"/api/compute/simulator/operations?operationKey=http-stop",null,admin);assertEquals(1,operation.path("effect_count").asInt());assertTrue(operation.path("receipt").path("simulation").asBoolean());
        var after=call(HttpMethod.GET,"/api/compute/simulator/nodes/a800-demo",null,admin);assertFalse(after.path("deployments").path("2").path("running").asBoolean());assertTrue(after.path("gpus").path("5").path("owner").isNull());
        assertEquals(2,j.queryForObject("SELECT current_hold_id FROM gpu_group WHERE id=2",Long.class));
        assertEquals(500,http.exchange("/api/compute/simulator/scenarios",HttpMethod.PUT,new HttpEntity<>(Map.of("operationKey","http-stop","mode","FAILURE"),admin),JsonNode.class).getBody().path("code").asInt());
    }
}
