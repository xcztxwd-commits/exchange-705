package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.user.UserActivityService;
import com.gtcfesk.exchange.simulation.AccountInspection;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Dedicated read projections: no reuse of business endpoints that mark records read or change balances. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/control")
public class ControlReadController {
    private final UserActivityService activity;
    private final AccountInspection inspection;
    private final TenantRepository tenants;
    private final ControlAuditService audit;
    private final JdbcTemplate jdbc;
    private final ControlReadQueryService queries;
    private Long actor(Long tenant) {
        ControlIdentity identity=ControlIdentity.current();
        if(identity==null||identity.getAccessSessionId()!=null)throw new org.springframework.security.access.AccessDeniedException("需要独立总控身份");
        Long actor = identity.getActorId();
        if(tenant==null) return ControlIdentity.requireIndependent();
        else tenants.findById(tenant).orElseThrow(() -> new IllegalArgumentException("租户不可用"));
        return actor;
    }
    private <T> T scoped(Long tenant,String action,String object,java.util.function.Supplier<T> query) {
        Long actor=actor(tenant);
        try(TenantContext.Scope ignored=TenantContext.open(tenant)) {
            T result=query.get();audit.record(actor,tenant,null,action,object,"SUCCESS","read-only",null);return result;
        } catch(RuntimeException failure) {audit.failure(actor,tenant,null,action,object);throw failure;}
    }
    private <T> T global(String action,String object,java.util.function.Supplier<T> query) {
        Long actor=actor(null);
        try {T result=query.get();audit.record(actor,null,null,action,object,"SUCCESS","read-only;scope=all",null);return result;}
        catch(RuntimeException failure){audit.failure(actor,null,null,action,object);throw failure;}
    }
    @GetMapping("/supervision/{kind:kyc|admins|agents}") public Object allRecords(@PathVariable String kind,
            @RequestParam(required=false) Long subjectId,@RequestParam(required=false) String userEmail,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ControlController.ok(global("SUPERVISION_READ_ALL",kind+";scope=all;page="+page,()->queries.allRecords(kind,subjectId,userEmail,status,page,size)));
    }
    @GetMapping("/supervision/statistics") public Object allStatistics() {
        return ControlController.ok(global("STATISTICS_READ_ALL","statistics;scope=all",queries::allStatistics));
    }
    @GetMapping("/business/{kind}") public Object allBusiness(@PathVariable String kind,
            @RequestParam(required=false) Long userId,@RequestParam(required=false) String userEmail,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ControlController.ok(global("BUSINESS_READ_ALL",kind+";scope=all;page="+page,()->inspection.readAll(kind,userId,userEmail,status,page,size)));
    }
    @GetMapping("/tenants/{tenant}/supervision/{kind:kyc|admins|agents}") public Object records(@PathVariable Long tenant,@PathVariable String kind,
            @RequestParam(required=false) Long subjectId,@RequestParam(required=false) String userEmail,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ControlController.ok(scoped(tenant,"SUPERVISION_READ",kind,()->queries.records(kind,subjectId,userEmail,status,page,size)));
    }
    @GetMapping("/tenants/{tenant}/supervision/statistics") public Object statistics(@PathVariable Long tenant) {
        return ControlController.ok(scoped(tenant,"STATISTICS_READ","tenant",queries::statistics));
    }
    @GetMapping("/tenants/{tenant}/support/conversations/{id}/images/{message}") public org.springframework.http.ResponseEntity<byte[]> image(@PathVariable Long tenant,@PathVariable long id,@PathVariable long message) {
        byte[] bytes=scoped(tenant,"CHAT_ATTACHMENT_READ",id+":"+message,()->queries.attachment(id,message));
        return com.gtcfesk.exchange.support.UserSupportController.privateImage(bytes);
    }
    @GetMapping("/tenants/{tenant}/support/conversations/{id}/evidence") public org.springframework.http.ResponseEntity<?> evidence(@PathVariable Long tenant,@PathVariable long id) {
        Map<String,Object> data=scoped(tenant,"CHAT_EVIDENCE_EXPORT",Long.toString(id),()->queries.evidence(id));
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_JSON).cacheControl(org.springframework.http.CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff").header("X-Evidence-Chain-Valid",String.valueOf(data.get("chainValid")))
            .header("Content-Disposition","attachment; filename=tenant-"+tenant+"-conversation-"+id+".json").body(data);
    }
    @GetMapping("/tenants/{tenant}/online") public Object online(@PathVariable Long tenant, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size, @RequestParam(required=false) String userEmail) {
        Long actor=actor(tenant);
        try (TenantContext.Scope ignored=TenantContext.open(tenant)) {
            Object result=activity.list(null,page,size,userEmail); audit.record(actor,tenant,null,"ONLINE_READ","users","SUCCESS","page="+page,null); return result;
        }
    }
    @GetMapping("/tenants/{tenant}/business/{kind}") public Object business(@PathVariable Long tenant,@PathVariable String kind,
            @RequestParam(required=false) Long userId,@RequestParam(required=false) String userEmail,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        Long actor=actor(tenant);
        try(TenantContext.Scope ignored=TenantContext.open(tenant)) {
            Object result=inspection.read(kind,userId,userEmail,status,page,size);
            audit.record(actor,tenant,null,"BUSINESS_READ",kind,"SUCCESS","page="+page,null);
            return ControlController.ok(result);
        }
    }
    @GetMapping("/support/conversations") public Object allConversations(
            @RequestParam(required=false) Long userId,@RequestParam(required=false) String userEmail,@RequestParam(required=false) Long adminId,
            @RequestParam(required=false) String createdFrom,@RequestParam(required=false) String createdTo,
            @RequestParam(defaultValue="0") int page) {
        String object="conversations;scope=all;page="+page+";from="+Objects.toString(createdFrom,"")+";to="+Objects.toString(createdTo,"");
        return ControlController.ok(global("CHAT_SUPERVISE_ALL",object,()->queries.allConversations(userId,adminId,userEmail,createdFrom,createdTo,page)));
    }
    @GetMapping("/tenants/{tenant}/support/conversations") public Object conversations(@PathVariable Long tenant,
            @RequestParam(required=false) Long userId,@RequestParam(required=false) String userEmail,@RequestParam(required=false) Long adminId,
            @RequestParam(required=false) String createdFrom,@RequestParam(required=false) String createdTo,
            @RequestParam(defaultValue="0") int page) {
        return ControlController.ok(scoped(tenant,"CHAT_SUPERVISE","conversations;page="+page+";from="+Objects.toString(createdFrom,"")+";to="+Objects.toString(createdTo,""),()->queries.conversations(userId,adminId,userEmail,createdFrom,createdTo,page)));
    }
    @GetMapping("/tenants/{tenant}/support/conversations/{id}") public Object conversation(@PathVariable Long tenant,@PathVariable Long id,
            @RequestParam(defaultValue="0") long after) {
        Long actor=actor(tenant);if(after<0)throw new IllegalArgumentException("分页参数无效");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM support_conversation WHERE tenant_id=? AND id=?",Long.class,tenant,id)!=1)throw new IllegalArgumentException("会话不存在");
        Object rows=jdbc.queryForList("SELECT id,conversation_id,sender,sender_id,sender_name,text,image,image_hash,previous_hash,hash,created_at FROM support_message WHERE tenant_id=? AND conversation_id=? AND id>? ORDER BY id LIMIT 100",tenant,id,after);
        audit.record(actor,tenant,null,"CHAT_READ",id.toString(),"SUCCESS","after="+after,null);return ControlController.ok(rows);
    }
}
