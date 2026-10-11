package dev.dangwenxiang.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class ModelGateway {
    private final String url,key,name;
    private final ObjectMapper mapper;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public ModelGateway(@Value("${agent.model-url}") String url,@Value("${agent.model-key}") String key,@Value("${agent.model-name}") String name,ObjectMapper mapper) {
        this.url=url;this.key=key;this.name=name;this.mapper=mapper;
    }
    public boolean configured(){return !url.isBlank()&&!name.isBlank();}
    public String model(){return configured()?name:"未配置模型";}
    public JsonNode next(List<Map<String,String>> messages) throws Exception {
        String endpoint=url.replaceAll("/+$","")+"/chat/completions";
        var uri=URI.create(endpoint);
        if(!Set.of("http","https").contains(uri.getScheme()))throw new IllegalArgumentException("Invalid scheme");
        var payload=Map.of("model",name,"messages",messages,"temperature",0,"max_tokens",1200);
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(!key.isBlank())builder.header("Authorization","Bearer "+key);
        var response=client.send(builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("Model HTTP "+response.statusCode());
        String content=mapper.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
        if(content.length()>20000)throw new IllegalStateException("Model output too long");
        content=content.strip().replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");
        return mapper.readTree(content);
    }
}
