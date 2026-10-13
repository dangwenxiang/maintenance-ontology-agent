package dev.dangwenxiang.maintenance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;

@Repository
public class Store {
    final JdbcTemplate db;
    final ObjectMapper mapper;
    public Store(JdbcTemplate db, ObjectMapper mapper) { this.db=db; this.mapper=mapper; }
    public record Device(String id, String model, String location, String configVersion) {}
    public record Case(String id, String deviceId, String model, String phenomenon, String cause,
                       String action, String result, String status, String reviewer, String reviewedAt) {}
    public record Document(String id, String title, String version, String model, String configVersion,
                           String section, String content, String status) {}

    @PostConstruct
    @Transactional
    public void seed() {
        if (db.queryForObject("select count(*) from device", Integer.class)>0) return;
        db.update("insert into device values (?,?,?,?)", "D-DEMO-01","TIGHTEN-DEMO-A","演示区域A","A-1");
        db.update("insert into device values (?,?,?,?)", "D-DEMO-02","TIGHTEN-DEMO-A","演示区域B","A-1");
        db.update("insert into device values (?,?,?,?)", "D-DEMO-03","TIGHTEN-DEMO-B","演示区域C","B-1");
        addCase("C-DEMO-001","D-DEMO-01","扭矩不合格","配置版本不匹配","按经确认资料核对配置","演示结果：恢复","approved");
        addCase("C-DEMO-002","D-DEMO-02","扭矩不合格","工具部件磨损","依据维护规程处理部件","演示结果：恢复","approved");
        addCase("C-DEMO-003","D-DEMO-03","扭矩不合格","型号B专用原因","型号B处理记录","演示结果：恢复","approved");
        addCase("C-DEMO-004","D-DEMO-01","扭矩不合格","待确认","待审核反馈","尚未确认","pending");
        doc("DOC-A-1","演示设备维护说明","1.0","TIGHTEN-DEMO-A","A-1","3.2 扭矩结果异常", "演示资料：出现扭矩不合格时，先记录设备编号、配置版本、发生频率和结果记录。确认配置是否适用；部件检查与处理必须依据正式设备规程，本演示不提供操作参数。", "active");
        doc("DOC-A-OLD","演示设备维护说明（停用）","0.9","TIGHTEN-DEMO-A","A-1","旧版说明", "已停用演示资料，不得作为新回答依据。", "retired");
        doc("DOC-B-1","型号B演示资料","1.0","TIGHTEN-DEMO-B","B-1","B 专用范围", "型号B的专用资料不适用于型号A。发生扭矩不合格时记录型号B专用状态；当前资料不提供具体维修操作。", "active");
    }
    private void addCase(String id,String device,String phenomenon,String cause,String action,String result,String status) {
        db.update("insert into maintenance_case (id,device_id,phenomenon,cause,action_summary,result,review_status,reviewer,reviewed_at,created_at) values (?,?,?,?,?,?,?,?,?,?)",id,device,phenomenon,cause,action,result,status,status.equals("approved")?"演示种子审核":null,status.equals("approved")?Instant.now().toString():null,Instant.now().toString());
    }
    private void doc(String id,String title,String version,String model,String config,String section,String content,String status) {
        db.update("insert into knowledge_document values (?,?,?,?,?,?,?,?)",id,title,version,model,config,section,content,status);
    }
    public List<Device> devices() {
        return db.query("select * from device order by id",(r,n)->new Device(r.getString("id"),r.getString("model"),r.getString("location"),r.getString("config_version")));
    }
    public Device device(String id) { return devices().stream().filter(d->d.id().equals(id)).findFirst().orElse(null); }
    public List<Case> cases(String model, String phenomenon, boolean includePending) {
        String sql="select c.*, d.model from maintenance_case c join device d on c.device_id=d.id where 1=1";
        List<Object> args=new ArrayList<>();
        if(model!=null&&!model.isBlank()){sql+=" and d.model=?";args.add(model);}
        if(phenomenon!=null&&!phenomenon.isBlank()){sql+=" and c.phenomenon=?";args.add(phenomenon);}
        if(!includePending)sql+=" and c.review_status='approved'";
        return db.query(sql+" order by c.created_at desc, c.id",(r,n)->new Case(r.getString("id"),r.getString("device_id"),r.getString("model"),r.getString("phenomenon"),r.getString("cause"),r.getString("action_summary"),r.getString("result"),r.getString("review_status"),r.getString("reviewer"),r.getString("reviewed_at")),args.toArray());
    }
    public List<Document> documents(Device device,String phenomenon) {
        if(phenomenon==null) return List.of();
        return db.query("select * from knowledge_document where model=? and config_version=? and status='active' order by id",(r,n)->new Document(r.getString("id"),r.getString("title"),r.getString("version"),r.getString("model"),r.getString("config_version"),r.getString("section"),r.getString("content"),r.getString("status")),device.model(),device.configVersion()).stream().filter(d->d.content().contains(phenomenon)).toList();
    }
    public List<Document> allDocuments() {
        return db.query("select * from knowledge_document order by id",(r,n)->new Document(r.getString("id"),r.getString("title"),r.getString("version"),r.getString("model"),r.getString("config_version"),r.getString("section"),r.getString("content"),r.getString("status")));
    }
    @Transactional
    public String publishDocument(String title,String version,String model,String config,String section,String content) {
        if(devices().stream().noneMatch(d->d.model().equals(model)&&d.configVersion().equals(config)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"型号或配置版本不存在");
        if(db.queryForObject("select count(*) from knowledge_document where title=? and version=? and model=? and config_version=?",Integer.class,title,version,model,config)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该资料版本已存在，请使用新版本");
        db.update("update knowledge_document set status='retired' where title=? and model=? and config_version=? and status='active'",title,model,config);
        String id="DOC-"+UUID.randomUUID();doc(id,title,version,model,config,section,content,"active");return id;
    }
    public synchronized void saveTrace(Map<String,Object> response) {
        if(db.queryForObject("select count(*) from question_trace where id=?",Integer.class,response.get("id"))>0)return;
        try { db.update("insert into question_trace values (?,?,?,?,?)",response.get("id"),response.get("question"),response.get("deviceId"),mapper.writeValueAsString(response),Instant.now().toString()); }
        catch(JsonProcessingException e){throw new IllegalStateException(e);}
    }
    public Map<String,Object> trace(String id) {
        var rows=db.queryForList("select response_json from question_trace where id=?",String.class,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"查询记录不存在");
        try { return mapper.readValue(rows.getFirst(),mapper.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,Object.class)); }
        catch(JsonProcessingException e){throw new IllegalStateException(e);}
    }
    @Transactional
    public String feedback(String traceId,String cause,String action,String result) {
        var trace=trace(traceId);
        String deviceId=(String)trace.get("deviceId");
        if(deviceId==null||device(deviceId)==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先确定设备，再提交反馈");
        String caseId="C-"+UUID.randomUUID(), id="F-"+UUID.randomUUID();
        String phenomenon=(String)trace.get("phenomenon");
        if(phenomenon==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先明确故障现象，再提交反馈");
        addCase(caseId,deviceId,phenomenon,cause,action,result,"pending");
        db.update("insert into feedback values (?,?,?,?)",id,traceId,caseId,Instant.now().toString());
        return caseId;
    }
    @Transactional
    public void review(String id,boolean approve,String reviewer) {
        int rows=db.update("update maintenance_case set review_status=?,reviewer=?,reviewed_at=? where id=? and review_status='pending'",approve?"approved":"rejected",reviewer,Instant.now().toString(),id);
        if(rows!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"记录不存在或已审核");
    }
}
