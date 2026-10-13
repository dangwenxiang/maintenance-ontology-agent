package dev.dangwenxiang.maintenance;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.regex.Pattern;

/** Business evidence and runtime boundary. The agent loop lives in official DSH packages. */
@Service
public class AgentService {
    private final Store store;
    private final DshClient dsh;
    public AgentService(Store store,DshClient dsh){this.store=store;this.dsh=dsh;}
    public Map<String,Object> evidence(String question,String selectedDevice){
        var response=new LinkedHashMap<String,Object>();response.put("question",question);
        String deviceId=selectedDevice;
        var match=Pattern.compile("D-[A-Z0-9-]+",Pattern.CASE_INSENSITIVE).matcher(question);
        if(match.find())deviceId=match.group().toUpperCase(Locale.ROOT);
        Store.Device device=deviceId==null?null:store.device(deviceId);
        response.put("deviceId",device==null?null:device.id());
        String phenomenon=(question.contains("扭矩")||question.contains("拧紧不合格"))?"扭矩不合格":null;
        response.put("phenomenon",phenomenon);
        if(device==null){
            response.put("status","needs_clarification");response.put("mode","evidence");
            response.put("answer",deviceId==null?"请先选择设备或提供设备编号，以确定适用的型号与资料版本。":"未找到设备 "+deviceId+"，请核对编号；未使用其他设备资料代替。");
            response.put("cases",List.of());response.put("documents",List.of());response.put("citations",List.of());response.put("trace",List.of());return response;
        }
        var allCases=phenomenon==null?List.<Store.Case>of():store.cases(device.model(),phenomenon,false);
        boolean sameModel=question.contains("同型号")||question.contains("同类");
        var cases=sameModel?allCases:allCases.stream().filter(c->c.deviceId().equals(device.id())).toList();
        var docs=store.documents(device,phenomenon);
        response.put("device",device);response.put("cases",cases);response.put("documents",docs);
        response.put("status",cases.isEmpty()&&docs.isEmpty()?"insufficient_evidence":"answered");
        response.put("mode","evidence");response.put("answer",baseline(device,cases,docs,phenomenon,sameModel));
        var valid=new LinkedHashSet<String>();cases.forEach(c->valid.add(c.id()));docs.forEach(d->valid.add(d.id()));
        response.put("citations",new ArrayList<>(valid));
        response.put("trace",List.of(Map.of("step",1,"tool","get_device","result",device),Map.of("step",2,"tool","search_cases","result",cases),Map.of("step",3,"tool","search_documents","result",docs)));
        return response;
    }
    public Map<String,Object> startRun(String question,String deviceId,String sessionId){
        var scope=evidence(question,deviceId);
        if(scope.get("deviceId")==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,scope.get("answer").toString());
        return dsh.submit(question,scope.get("deviceId").toString(),sessionId);
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> run(String id,int cursor){
        var result=dsh.run(id,cursor);
        if("completed".equals(result.get("status"))&&result.get("response") instanceof Map<?,?>)
            store.saveTrace((Map<String,Object>)result.get("response"));
        return result;
    }
    public Map<String,Object> ask(String question,String selectedDevice){return ask(question,selectedDevice,null);}
    @SuppressWarnings("unchecked")
    public Map<String,Object> ask(String question,String selectedDevice,String sessionId){
        var evidence=evidence(question,selectedDevice);
        if(!dsh.enabled()||evidence.get("deviceId")==null){
            evidence.put("id","Q-"+UUID.randomUUID());store.saveTrace(evidence);return evidence;
        }
        var running=startRun(question,selectedDevice,sessionId);String id=running.get("id").toString();
        long deadline=System.nanoTime()+140_000_000_000L;
        while(System.nanoTime()<deadline){
            var state=run(id,0);String status=state.get("status").toString();
            if(status.equals("completed"))return (Map<String,Object>)state.get("response");
            if(!List.of("running","cancelling").contains(status))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"DSH任务未完成："+status);
            try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        dsh.cancel(id);throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,"DSH执行超时");
    }
    private String baseline(Store.Device d,List<Store.Case> cases,List<Store.Document> docs,String phenomenon,boolean sameModel){
        if(phenomenon==null)return "当前首版只收录“扭矩不合格”及其同义描述。该现象没有可核对的资料，请补充故障现象或导入适用资料；未生成维修步骤。";
        if(cases.isEmpty()&&docs.isEmpty())return "没有找到适用于当前设备与现象的已审核案例或有效资料。请补充记录，不据此推断原因。";
        var answer=new StringBuilder("设备 "+d.id()+"，型号 "+d.model()+"，配置 "+d.configVersion()+"。\n\n");
        answer.append(sameModel?"同型号历史案例中的已确认原因：\n":"当前设备历史案例中的已确认原因：\n");
        if(cases.isEmpty())answer.append("暂无已审核记录。\n");
        for(var c:cases)answer.append("• ").append(c.cause()).append("；处理记录：").append(c.action()).append("；").append(c.result()).append(" [").append(c.id()).append("]\n");
        if(!docs.isEmpty()){answer.append("\n适用资料：\n");for(var doc:docs)answer.append(doc.content()).append(" [").append(doc.id()).append("]\n");}
        answer.append("\n这些记录不能证明当前故障原因相同。请确认发生频率、当前配置版本、结果记录与最近变更；具体操作需要正式设备规程。全部来源为合成演示数据。");
        return answer.toString();
    }
}
