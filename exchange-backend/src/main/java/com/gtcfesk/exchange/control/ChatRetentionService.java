package com.gtcfesk.exchange.control;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.gtcfesk.exchange.support.SupportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

/** Closed conversations are the indivisible retention unit. Default policy never deletes. */
@Service @RequiredArgsConstructor
public class ChatRetentionService {
    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final TenantPolicyRepository policies;
    private final ControlAuditService audit;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${control.retention.archive-directory:}") private String archiveDirectory;
    @org.springframework.beans.factory.annotation.Autowired private ChatArchiveService archives;
    private static final String ENABLED="retention.auto_delete_enabled";
    private static final String APPROVER="retention.approved_by";
    private static final String CURSOR="retention.scan_cursor";
    private static final long MAX_ARCHIVE_BYTES=32L*1024*1024;
    private Timestamp cutoff(){return Timestamp.from(Instant.now().minus(365,java.time.temporal.ChronoUnit.DAYS));}
    private List<Long> candidates(Long tenant) {
        long cursor=policies.findByTenantIdAndKey(tenant,CURSOR).map(p->Long.parseLong(p.getValue())).orElse(0L);if(cursor<0)throw new IllegalStateException("留存游标损坏");
        List<Long> ids=jdbc.queryForList("SELECT id FROM support_conversation WHERE tenant_id=? AND id>? AND status='CLOSED' AND closed_at<? AND legal_hold=0 ORDER BY id LIMIT 100",Long.class,tenant,cursor,cutoff());
        // Wrap only after reaching the end: a preserved head cannot starve later conversations, and old skipped rows are rechecked on the next sweep.
        if(ids.isEmpty()&&cursor>0)ids=jdbc.queryForList("SELECT id FROM support_conversation WHERE tenant_id=? AND status='CLOSED' AND closed_at<? AND legal_hold=0 ORDER BY id LIMIT 100",Long.class,tenant,cutoff());return ids;
    }
    public Map<String,Object> preview(Long tenant) {
        ControlIdentity.actorId();Tenant t=tenants.findById(tenant).orElseThrow(ControlService::invalid);
        List<Long> ids=candidates(tenant); Map<String,Object> out=new LinkedHashMap<>();
        out.put("retentionDays",365);out.put("autoDeleteEnabled",policies.findByTenantIdAndKey(tenant,ENABLED).map(p->"true".equals(p.getValue())).orElse(false));
        out.put("candidateIds",ids);out.put("policyVersion",t.getPolicyVersion());out.put("previewHash",SupportService.sha256(ids.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        out.put("candidateCount",jdbc.queryForObject("SELECT COUNT(*) FROM support_conversation WHERE tenant_id=? AND status='CLOSED' AND closed_at<? AND legal_hold=0",Long.class,tenant,cutoff()));
        out.put("attachmentBytes",jdbc.queryForObject("SELECT COALESCE(SUM(OCTET_LENGTH(a.content)),0) FROM support_attachment a JOIN support_message m ON m.tenant_id=a.tenant_id AND m.id=a.message_id JOIN support_conversation c ON c.tenant_id=m.tenant_id AND c.id=m.conversation_id WHERE c.tenant_id=? AND c.status='CLOSED' AND c.closed_at<? AND c.legal_hold=0",Long.class,tenant,cutoff()));return out;
    }
    @Transactional public void configure(Long tenant,boolean enabled,boolean confirm,long expectedVersion,String previewHash,String reason) {
        Long actor=ControlIdentity.actorId();TenantManagementService.reason(reason);Tenant t=tenants.lock(tenant).orElseThrow(ControlService::invalid);
        if(enabled){
            if(!confirm)throw new IllegalArgumentException("启用自动清理必须预览并明确确认");
            requirePreview(t,expectedVersion,previewHash);verifyArchiveDirectory();
            TenantPolicy author=policies.findByTenantIdAndKey(tenant,APPROVER).orElseGet(TenantPolicy::new);
            author.setTenantId(tenant);author.setKey(APPROVER);author.setValue(actor.toString());author.setLocked(true);policies.save(author);
        }
        TenantPolicy p=policies.findByTenantIdAndKey(tenant,ENABLED).orElseGet(TenantPolicy::new);p.setTenantId(tenant);p.setKey(ENABLED);p.setValue(Boolean.toString(enabled));p.setLocked(true);policies.save(p);t.setPolicyVersion(t.getPolicyVersion()+1);
        audit.record(actor,tenant,null,"RETENTION_POLICY",ENABLED,"SUCCESS","enabled="+enabled,reason);
    }
    @Transactional public void hold(Long tenant,Long id,boolean hold,String reason) {
        Long actor=ControlIdentity.actorId();TenantManagementService.reason(reason);tenants.lock(tenant).orElseThrow(ControlService::invalid);
        if(jdbc.update("UPDATE support_conversation SET legal_hold=? WHERE tenant_id=? AND id=?",hold,tenant,id)!=1)throw new IllegalArgumentException("会话不存在");
        audit.record(actor,tenant,null,"CHAT_LEGAL_HOLD",id.toString(),"SUCCESS","hold="+hold,reason);
    }
    @Transactional public int clean(Long tenant,long expectedVersion,String previewHash,String reason) {
        Long actor=ControlIdentity.actorId();TenantManagementService.reason(reason);Tenant t=tenants.lock(tenant).orElseThrow(ControlService::invalid);
        if(!policies.findByTenantIdAndKey(tenant,ENABLED).map(p->"true".equals(p.getValue())).orElse(false))return 0;
        return deleteBatch(tenant,requirePreview(t,expectedVersion,previewHash),actor,t.getPolicyVersion(),reason,false);
    }
    /** A scheduled job is SYSTEM, not the human who previously approved the policy. */
    @Transactional public int cleanAutomatically(Long tenant){
        Tenant t=tenants.lock(tenant).orElseThrow(ControlService::invalid);
        if(!policies.findByTenantIdAndKey(tenant,ENABLED).map(p->"true".equals(p.getValue())).orElse(false))return 0;
        String approver=policies.findByTenantIdAndKey(tenant,APPROVER).map(TenantPolicy::getValue).orElseThrow(()->new IllegalStateException("缺少留存策略批准记录"));
        return deleteBatch(tenant,candidates(tenant),null,t.getPolicyVersion(),"SYSTEM retention job; policy approved by control actor "+approver,true);
    }
    private List<Long> requirePreview(Tenant t,long expectedVersion,String previewHash){
        if(t.getPolicyVersion()!=expectedVersion)throw new IllegalArgumentException("策略已变化，请重新预览");
        List<Long> ids=candidates(t.getId());
        if(!SupportService.sha256(ids.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(previewHash))throw new IllegalArgumentException("候选范围已变化，请重新预览");
        return ids;
    }
    private int deleteBatch(Long tenant,List<Long> ids,Long actor,long version,String reason,boolean automatic){
        verifyArchiveDirectory();
        int deleted=0;
        for(Long id:ids){
            List<Map<String,Object>> conversation=jdbc.queryForList("SELECT * FROM support_conversation WHERE tenant_id=? AND id=? AND status='CLOSED' AND closed_at<? AND legal_hold=0 FOR UPDATE",tenant,id,cutoff());
            if(conversation.isEmpty())continue;
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM support_message WHERE tenant_id=? AND conversation_id=?",Long.class,tenant,id);
            Long size=jdbc.queryForObject("SELECT COALESCE(SUM(OCTET_LENGTH(a.content)),0) FROM support_attachment a JOIN support_message m ON m.tenant_id=a.tenant_id AND m.id=a.message_id WHERE m.tenant_id=? AND m.conversation_id=?",Long.class,tenant,id);
            // Oversized conversations progress as restartable jobs; failed/pending/unproved jobs preserve all source rows.
            if(count>10000||size>MAX_ARCHIVE_BYTES){
                String complete=archives.retentionArchive(tenant,id,actor,reason);
                if(complete==null){audit.record(actor,tenant,null,"CHAT_RETENTION_SKIPPED",id.toString(),"PRESERVED","policyVersion="+version+";reason=streaming-archive-pending-or-failed",reason);continue;}
                verifyRestoreProof("retention-chunks-v2",3,".retention-chunks-restore");
                jdbc.update("DELETE a FROM support_attachment a JOIN support_message m ON m.tenant_id=a.tenant_id AND m.id=a.message_id WHERE m.tenant_id=? AND m.conversation_id=?",tenant,id);
                jdbc.update("DELETE FROM support_message WHERE tenant_id=? AND conversation_id=?",tenant,id);
                deleted+=jdbc.update("DELETE FROM support_conversation WHERE tenant_id=? AND id=? AND legal_hold=0 AND status='CLOSED'",tenant,id);
                audit.record(actor,tenant,null,automatic?"CHAT_RETENTION_AUTOMATIC_DELETE":"CHAT_RETENTION_DELETE",id.toString(),"SUCCESS","policyVersion="+version+";messages="+count+";attachmentBytes="+size+";archive="+complete,reason);continue;
            }
            List<Map<String,Object>> messages=jdbc.queryForList("SELECT * FROM support_message WHERE tenant_id=? AND conversation_id=? ORDER BY id",tenant,id);
            List<Map<String,Object>> attachments=jdbc.queryForList("SELECT a.* FROM support_attachment a JOIN support_message m ON m.tenant_id=a.tenant_id AND m.id=a.message_id WHERE m.tenant_id=? AND m.conversation_id=?",tenant,id);
            Map<String,Object> data=new LinkedHashMap<>();data.put("tenantId",tenant);data.put("conversation",conversation);data.put("messages",messages);data.put("attachments",attachments);
            verifyMessageChain(tenant,id,data);
            String archive=archive(tenant,id,data);
            jdbc.update("DELETE a FROM support_attachment a JOIN support_message m ON m.tenant_id=a.tenant_id AND m.id=a.message_id WHERE m.tenant_id=? AND m.conversation_id=?",tenant,id);
            jdbc.update("DELETE FROM support_message WHERE tenant_id=? AND conversation_id=?",tenant,id);
            deleted+=jdbc.update("DELETE FROM support_conversation WHERE tenant_id=? AND id=? AND legal_hold=0 AND status='CLOSED'",tenant,id);
            audit.record(actor,tenant,null,automatic?"CHAT_RETENTION_AUTOMATIC_DELETE":"CHAT_RETENTION_DELETE",id.toString(),"SUCCESS","policyVersion="+version+";messages="+messages.size()+";attachments="+attachments.size()+";archive="+archive,reason);
        }
        if(!ids.isEmpty()){TenantPolicy progress=policies.findByTenantIdAndKey(tenant,CURSOR).orElseGet(TenantPolicy::new);progress.setTenantId(tenant);progress.setKey(CURSOR);progress.setValue(ids.get(ids.size()-1).toString());progress.setLocked(true);policies.save(progress);}
        return deleted;
    }
    private void verifyArchiveDirectory(){verifyRestoreProof("retention-json-v1",2,".retention-restore");}
    private void verifyRestoreProof(String format,int toolVersion,String prefix){
        if(archiveDirectory==null||archiveDirectory.trim().isEmpty())throw new IllegalStateException("未配置受限归档目录");
        try{
            Path base=Paths.get(archiveDirectory).toAbsolutePath().normalize();
            if(!Files.isDirectory(base)||Files.isSymbolicLink(base))throw new IllegalStateException("归档目录必须预先创建且不能为符号链接");
            Path proof=base.resolve(prefix+"-verified");
            if(!Files.isRegularFile(proof,LinkOption.NOFOLLOW_LINKS)||Files.size(proof)>4096)throw new IllegalStateException("请先完成独立数据库归档恢复演练");
            com.fasterxml.jackson.databind.JsonNode marker=json.readTree(Files.readAllBytes(proof));Path report=base.resolve(prefix+"-report.json");
            if(marker.path("toolVersion").asInt()!=toolVersion||!format.equals(marker.path("format").asText())||!marker.path("reportSha256").asText().matches("[a-f0-9]{64}")||!Files.isRegularFile(report,LinkOption.NOFOLLOW_LINKS)||Files.size(report)>65536||!SupportService.sha256(Files.readAllBytes(report)).equals(marker.path("reportSha256").asText()))throw new IllegalStateException("恢复演练报告版本或哈希无效，旧手写标记不可启用清理");
            com.fasterxml.jackson.databind.JsonNode verified=json.readTree(Files.readAllBytes(report));
            if(!"RESTORED_AND_VERIFIED".equals(verified.path("result").asText())||verified.path("toolVersion").asInt()!=toolVersion||!verified.path("targetDatabase").asText().matches("mt705_restore_[a-z0-9_]+")||verified.path("sourceServerIdentity").asText().isEmpty()||verified.path("sourceServerIdentity").asText().equals(verified.path("targetServerIdentity").asText())||verified.path("targetServerIdentity").asText().isEmpty())throw new IllegalStateException("恢复报告必须绑定独立隔离目标");
            if(!format.equals(verified.path("format").asText())||!verified.path("databaseRestored").asBoolean()||!verified.path("independentCurrentBackupRestored").asBoolean())throw new IllegalStateException("恢复报告缺少实际完整恢复或格式不匹配");
            for(String check:Arrays.asList("rows","tenantRelations","messageChain","lastHash","attachmentBytes","schema"))if(!verified.path("checks").path(check).asBoolean())throw new IllegalStateException("归档恢复检查未通过: "+check);
        }catch(java.io.IOException e){throw new IllegalStateException("归档配置验证失败",e);}
    }
    private String archive(Long tenant,Long id,Map<String,Object> data) {
        if(archiveDirectory==null||archiveDirectory.trim().isEmpty())throw new IllegalStateException("未配置归档目录");
        try {
            Map<String,Object> precise=new LinkedHashMap<>(data);for(String key:Arrays.asList("conversation","messages","attachments")){List<Map<String,Object>> rows=new ArrayList<>();for(Object item:(List<?>)data.get(key)){Map<String,Object> row=new LinkedHashMap<>((Map<String,Object>)item);row.replaceAll((field,value)->ChatArchiveService.archiveValue(value));rows.add(row);}precise.put(key,rows);}
            byte[] bytes=json.writeValueAsBytes(precise);String hash=SupportService.sha256(bytes);
            Path base=Paths.get(archiveDirectory).toAbsolutePath().normalize(), directory=base.resolve("tenant-"+tenant);
            Files.createDirectories(directory);
            if(Files.isSymbolicLink(directory)||!directory.toRealPath().startsWith(base.toRealPath()))throw new IllegalStateException("归档目录边界无效");
            Path file=directory.resolve(id+"-"+hash+".json");
            if(!Files.exists(file)){Path temp=Files.createTempFile(directory,"archive-",".tmp");Files.write(temp,bytes);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);}
            if(Files.isSymbolicLink(file)||!hash.equals(SupportService.sha256(Files.readAllBytes(file))))throw new IllegalStateException("归档校验失败");
            com.fasterxml.jackson.databind.JsonNode restored=json.readTree(Files.readAllBytes(file));
            if(restored.path("tenantId").asLong()!=tenant||restored.path("conversation").size()!=1||restored.path("messages").size()!=messagesSize(data,"messages")||restored.path("attachments").size()!=messagesSize(data,"attachments"))throw new IllegalStateException("归档恢复校验失败");
            return file.getFileName().toString();
        }catch(java.io.IOException e){throw new IllegalStateException("归档失败，未删除记录",e);}
    }
    @SuppressWarnings("unchecked") private void verifyMessageChain(Long tenant,Long conversation,Map<String,Object> data){
        String previous="";Map<Long,Map<String,Object>> images=new HashMap<>();
        for(Map<String,Object> message:(List<Map<String,Object>>)data.get("messages")){
            Object time=message.get("created_at");if(!(time instanceof java.sql.Timestamp)&&!(time instanceof java.time.LocalDateTime))throw new IllegalStateException("消息时间格式无效");
            String created=(String)ChatArchiveService.archiveValue(time);
            String expected;
            try{expected=SupportService.sha256(json.writeValueAsBytes(Arrays.asList(conversation,message.get("sender"),((Number)message.get("sender_id")).longValue(),message.get("sender_name"),message.get("request_id"),message.get("text"),message.get("image_hash"),created,previous)));}catch(java.io.IOException e){throw new IllegalStateException("消息链序列化失败",e);}
            if(!previous.equals(message.get("previous_hash"))||!expected.equals(message.get("hash")))throw new IllegalStateException("消息链无效，保留会话");previous=expected;
            Object flag=message.get("image");if(Boolean.TRUE.equals(flag)||flag instanceof Number&&((Number)flag).intValue()==1)images.put(((Number)message.get("id")).longValue(),message);
        }
        Map<String,Object> conversationRow=((List<Map<String,Object>>)data.get("conversation")).get(0);
        if(!previous.equals(conversationRow.get("last_hash")))throw new IllegalStateException("会话lastHash不匹配，保留会话");
        for(Map<String,Object> attachment:(List<Map<String,Object>>)data.get("attachments")){
            Map<String,Object> message=images.remove(((Number)attachment.get("message_id")).longValue());Object content=attachment.get("content");
            if(message==null||!(content instanceof byte[]))throw new IllegalStateException("附件归属无效，保留会话");byte[] binary=(byte[])content;
            if(binary.length<8||binary[0]!=(byte)137||binary[1]!=80||binary[2]!=78||binary[3]!=71||binary[4]!=13||binary[5]!=10||binary[6]!=26||binary[7]!=10||!SupportService.sha256(binary).equals(message.get("image_hash")))throw new IllegalStateException("附件完整性无效，保留会话");
        }
        if(!images.isEmpty())throw new IllegalStateException("缺少附件，保留会话");
    }
    private int messagesSize(Map<String,Object> data,String key){return ((List<?>)data.get(key)).size();}
}
