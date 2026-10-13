package dev.dangwenxiang.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class DshClient {
    private final String url,token;
    private final boolean enabled;
    private final ObjectMapper mapper;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public DshClient(@Value("${agent.dsh-url}") String url,@Value("${agent.dsh-token}") String token,
                     @Value("${agent.dsh-enabled}") boolean enabled,ObjectMapper mapper){
        this.url=url;this.token=token;this.enabled=enabled;this.mapper=mapper;
    }
    public boolean enabled(){return enabled;}
    public Map<String,Object> health(){
        if(!enabled)return Map.of("status","disabled","kernel","none");
        try{return request("GET","/v1/health",null);}catch(ResponseStatusException e){return Map.of("status","unavailable","kernel","dsh");}
    }
    public Map<String,Object> submit(String question,String deviceId,String sessionId){
        var body=new LinkedHashMap<String,Object>();body.put("question",question);body.put("deviceId",deviceId);
        if(sessionId!=null&&!sessionId.isBlank())body.put("sessionId",sessionId);
        return request("POST","/v1/runs",body);
    }
    public Map<String,Object> run(String id,int cursor){validId(id);return request("GET","/v1/runs/"+id+"?cursor="+cursor,null);}
    public Map<String,Object> cancel(String id){validId(id);return request("POST","/v1/runs/"+id+"/cancel",Map.of());}
    public Object sessions(){return requestValue("GET","/v1/sessions",null);}
    private void validId(String id){if(id==null||!id.matches("Q-[a-f0-9-]{36}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"任务编号无效");}
    @SuppressWarnings("unchecked")
    private Map<String,Object> request(String method,String path,Object body){return (Map<String,Object>)requestValue(method,path,body);}
    private Object requestValue(String method,String path,Object body){
        if(!enabled||token.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"DSH未启用，请使用整套启动脚本");
        try {
            var builder=HttpRequest.newBuilder(URI.create(url+path)).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json");
            if(method.equals("GET"))builder.GET();else builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()>=400){
                String reason=mapper.readTree(response.body()).path("error").asText("DSH服务请求失败");
                throw new ResponseStatusException(HttpStatus.valueOf(response.statusCode()),reason);
            }
            return mapper.readValue(response.body(),Object.class);
        }catch(ResponseStatusException e){throw e;}catch(Exception e){
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"无法连接DSH运行服务");
        }
    }
}
