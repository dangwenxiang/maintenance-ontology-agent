package dev.dangwenxiang.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:behavior;DB_CLOSE_DELAY=-1","agent.admin-token=test-only-token","agent.model-url=","agent.model-name="})
@AutoConfigureMockMvc
@Transactional
class BehaviorTest {
    @Autowired Store store;@Autowired AgentService service;@Autowired MockMvc mvc;@Autowired ObjectMapper mapper;
    @Test void sameModelIncludesOnlyApprovedMatchingCases(){
        var answer=service.ask("同型号设备扭矩不合格", "D-DEMO-01");
        var cases=(List<Store.Case>)answer.get("cases");
        assertEquals(Set.of("C-DEMO-001","C-DEMO-002"),new HashSet<>(cases.stream().map(Store.Case::id).toList()));
        assertTrue(cases.stream().allMatch(c->c.status().equals("approved")));
        var docs=(List<Store.Document>)answer.get("documents");
        assertEquals(List.of("DOC-A-1"),docs.stream().map(Store.Document::id).toList());
    }
    @Test void currentDeviceDoesNotIncludeNeighbor(){
        var a=service.ask("这台设备扭矩不合格", "D-DEMO-01");
        assertEquals(List.of("C-DEMO-001"),((List<Store.Case>)a.get("cases")).stream().map(Store.Case::id).toList());
    }
    @Test void unknownDeviceDoesNotFallBackToSelectedDevice(){
        var a=service.ask("D-UNKNOWN-99扭矩不合格", "D-DEMO-01");
        assertEquals("needs_clarification",a.get("status"));assertNull(a.get("deviceId"));
        assertEquals(List.of(),a.get("documents"));
    }
    @Test void missingEvidenceDoesNotInventProcedure(){
        var a=service.ask("设备异响该怎么处理？", "D-DEMO-01");
        assertEquals("insufficient_evidence",a.get("status"));assertEquals(List.of(),a.get("citations"));
        assertTrue(a.get("answer").toString().contains("未生成维修步骤"));
    }
    @Test void feedbackIsInvisibleUntilApprovedAndDuplicateReviewFails(){
        var a=service.ask("扭矩不合格", "D-DEMO-01");
        String id=store.feedback(a.get("id").toString(),"新的合成原因","演示处理","演示恢复");
        assertFalse(store.cases(null,null,false).stream().anyMatch(c->c.id().equals(id)));
        store.review(id,true,"测试审核者");
        assertTrue(store.cases(null,null,false).stream().anyMatch(c->c.id().equals(id)&&c.reviewer().equals("测试审核者")));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.review(id,true,"第二次审核"));
    }
    @Test void rejectedFeedbackNeverBecomesEvidence(){
        var a=service.ask("扭矩不合格", "D-DEMO-01");
        String id=store.feedback(a.get("id").toString(),"驳回原因","待确认","未确认");store.review(id,false,"审核者");
        assertFalse(store.cases(null,null,false).stream().anyMatch(c->c.id().equals(id)));
    }
    @Test void cannotAttachFeedbackToUnresolvedQuestion(){
        var a=service.ask("扭矩不合格",null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.feedback(a.get("id").toString(),"原因","处理","结果"));
    }
    @Test void newDocumentVersionRetiresOldButTraceKeepsOriginalEvidence(){
        var a=service.ask("扭矩不合格", "D-DEMO-01");
        String id=store.publishDocument("演示设备维护说明","2.0","TIGHTEN-DEMO-A","A-1","3.2","新版扭矩不合格资料");
        assertEquals(List.of(id),store.documents(store.device("D-DEMO-01"),"扭矩不合格").stream().map(Store.Document::id).toList());
        assertTrue(store.trace(a.get("id").toString()).get("documents").toString().contains("DOC-A-1"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.publishDocument("演示设备维护说明","2.0","TIGHTEN-DEMO-A","A-1","3.2","重复"));
    }
    @Test void adminEndpointsRejectMissingAndWrongToken() throws Exception{
        mvc.perform(get("/api/review")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/review").header("X-Admin-Token","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/review").header("X-Admin-Token","test-only-token")).andExpect(status().isOk());
        mvc.perform(post("/api/documents").contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
    }
    @Test void emptyQuestionRejected() throws Exception{
        mvc.perform(post("/api/ask").contentType("application/json").content("{\"question\":\"\"}")).andExpect(status().isBadRequest());
    }
    @Test void modelToolsAreExecutedAndCitationsMustBeObserved() throws Exception{
        var model=mock(ModelGateway.class);when(model.configured()).thenReturn(true);
        when(model.next(anyList())).thenReturn(mapper.readTree("{\"tool\":\"search_cases\"}"),mapper.readTree("{\"answer\":\"历史案例确认过配置版本不匹配，当前原因待核实。\",\"citations\":[\"C-DEMO-001\"]}"));
        var a=new AgentService(store,model,mapper).ask("扭矩不合格", "D-DEMO-01");
        assertEquals("model_tools",a.get("mode"));verify(model,times(2)).next(anyList());
        assertTrue(a.get("trace").toString().contains("search_cases"));
    }
    @Test void inventedCitationFallsBackToFacts() throws Exception{
        var model=mock(ModelGateway.class);when(model.configured()).thenReturn(true);
        when(model.next(anyList())).thenReturn(mapper.readTree("{\"answer\":\"假答案\",\"citations\":[\"MADE-UP\"]}"));
        var a=new AgentService(store,model,mapper).ask("扭矩不合格", "D-DEMO-01");
        assertEquals("evidence_fallback",a.get("mode"));assertFalse(a.get("answer").toString().contains("假答案"));
    }
    @Test void modelCannotExecuteWriteTool() throws Exception{
        var model=mock(ModelGateway.class);when(model.configured()).thenReturn(true);
        when(model.next(anyList())).thenReturn(mapper.readTree("{\"tool\":\"approve_case\"}"));
        var a=new AgentService(store,model,mapper).ask("扭矩不合格", "D-DEMO-01");
        assertEquals("evidence_fallback",a.get("mode"));
        assertTrue(store.cases(null,null,true).stream().anyMatch(c->c.id().equals("C-DEMO-004")&&c.status().equals("pending")));
    }
    @Test void toolLoopHasFiniteLimit() throws Exception{
        var model=mock(ModelGateway.class);when(model.configured()).thenReturn(true);
        when(model.next(anyList())).thenReturn(mapper.readTree("{\"tool\":\"get_device\"}"));
        var a=new AgentService(store,model,mapper).ask("扭矩不合格", "D-DEMO-01");
        assertEquals("evidence_fallback",a.get("mode"));verify(model,times(4)).next(anyList());
    }
}
