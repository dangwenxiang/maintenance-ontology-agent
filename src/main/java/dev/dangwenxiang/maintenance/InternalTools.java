package dev.dangwenxiang.maintenance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
public class InternalTools {
    private final AgentService agent;private final String token;
    public InternalTools(AgentService agent,@Value("${agent.dsh-tool-token}") String token){this.agent=agent;this.token=token;}
    public record Execute(String name,String question,String deviceId,String ownerId){}
    @PostMapping("/internal/dsh/tools")
    public Object execute(@RequestHeader(value="X-DSH-Tool-Token",required=false) String supplied,@RequestBody Execute request){
        if(token.isBlank()||supplied==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"工具服务凭证无效");
        if(!"local-demo".equals(request.ownerId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"首版仅支持本机演示身份");
        String question=Api.text(request.question(),"问题",1000),deviceId=Api.text(request.deviceId(),"设备",64);
        var evidence=agent.evidence(question,deviceId);
        // A runtime session is bound to one device. Text must not switch its tool scope.
        if(!deviceId.equals(evidence.get("deviceId")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"工具设备范围不匹配");
        Object result=switch(Objects.toString(request.name(),"")){
            case "get_device"->evidence.get("device");case "search_cases"->evidence.get("cases");case "search_documents"->evidence.get("documents");
            default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"工具未注册");
        };
        return Map.of("result",result,"evidence",evidence);
    }
}
