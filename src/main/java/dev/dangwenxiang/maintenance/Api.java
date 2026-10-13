package dev.dangwenxiang.maintenance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api")
public class Api {
    private final Store store;private final AgentService agent;private final DshClient dsh;private final String adminToken;
    public Api(Store store,AgentService agent,DshClient dsh,@Value("${agent.admin-token}") String token){this.store=store;this.agent=agent;this.dsh=dsh;this.adminToken=token;}
    @GetMapping("/health") public Object health(){return Map.of("status","ok","version","0.2.0","runtimeEnabled",dsh.enabled(),"runtime",dsh.health(),"reviewEnabled",!adminToken.isBlank(),"dataType","synthetic");}
    @GetMapping("/devices") public Object devices(){return store.devices();}
    @GetMapping("/cases") public Object cases(@RequestParam(required=false) String model){return store.cases(model,null,false);}
    @GetMapping("/documents") public Object documents(){return store.allDocuments();}
    public record PublishDocument(String title,String version,String model,String configVersion,String section,String content){}
    @PostMapping("/documents") public Object publish(@RequestHeader(value="X-Admin-Token",required=false) String token,@RequestBody PublishDocument d){auth(token);return Map.of("id",store.publishDocument(text(d.title(),"标题",200),text(d.version(),"版本",40),text(d.model(),"型号",64),text(d.configVersion(),"配置版本",40),text(d.section(),"章节",100),text(d.content(),"正文",3000)),"status","active");}
    @GetMapping("/ontology") public Object ontology(){
        return Map.of("concepts",List.of("设备","型号","部件","故障现象","故障原因","维修动作","维修案例","技术资料"),"relations",List.of(
            Map.of("from","设备","relation","属于","to","型号"),Map.of("from","设备","relation","包含","to","部件"),
            Map.of("from","维修案例","relation","发生于","to","设备"),Map.of("from","维修案例","relation","记录","to","故障现象"),
            Map.of("from","维修案例","relation","确认","to","故障原因"),Map.of("from","维修案例","relation","采用","to","维修动作"),
            Map.of("from","技术资料","relation","适用于","to","型号")),"note","这是首版领域关系定义，不是通用OWL推理引擎；历史确认原因不等于当前原因。");
    }
    public record Ask(String question,String deviceId,String sessionId){}
    @PostMapping("/ask") public Object ask(@RequestBody Ask request){return agent.ask(text(request.question(),"问题",1000),request.deviceId(),request.sessionId());}
    @PostMapping("/runs") public Object startRun(@RequestBody Ask request){return agent.startRun(text(request.question(),"问题",1000),request.deviceId(),request.sessionId());}
    @GetMapping("/runs/{id}") public Object run(@PathVariable String id,@RequestParam(defaultValue="0") int cursor){if(cursor<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"游标无效");return agent.run(id,cursor);}
    @PostMapping("/runs/{id}/cancel") public Object cancel(@PathVariable String id){return dsh.cancel(id);}
    @GetMapping("/sessions") public Object sessions(){return dsh.sessions();}
    @GetMapping("/traces/{id}") public Object trace(@PathVariable String id){return store.trace(id);}
    public record Feedback(String traceId,String cause,String action,String result){}
    @PostMapping("/feedback") public Object feedback(@RequestBody Feedback f){return Map.of("caseId",store.feedback(text(f.traceId(),"问答记录",64),text(f.cause(),"实际原因",500),text(f.action(),"处理过程",1000),text(f.result(),"结果",500)),"status","pending");}
    @GetMapping("/review") public Object pending(@RequestHeader(value="X-Admin-Token",required=false) String token){auth(token);return store.cases(null,null,true).stream().filter(c->c.status().equals("pending")).toList();}
    public record Review(boolean approve,String reviewer){}
    @PostMapping("/review/{id}") public Object review(@PathVariable String id,@RequestHeader(value="X-Admin-Token",required=false) String token,@RequestBody Review review){auth(token);store.review(id,review.approve(),text(review.reviewer(),"审核人",100));return Map.of("status",review.approve()?"approved":"rejected");}
    private void auth(String token){
        if(adminToken.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"未配置ADMIN_TOKEN，审核功能关闭");
        if(token==null||!MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"审核凭证无效");
    }
    static String text(String value,String field,int max){if(value==null||value.isBlank()||value.length()>max)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,field+"不能为空或超长");return value.strip();}
    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> error(ResponseStatusException e){return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("error",Objects.toString(e.getReason(),"请求失败")));}
}
