package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.tenant.*;
import com.gtcfesk.exchange.support.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.AccessDeniedException;
import javax.persistence.*;
import java.time.Instant;
import java.util.*;

/** Only bounded, fixed projections. Never calls a business read with update/read-receipt side effects. */
@Service @RequiredArgsConstructor
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, propagation=Propagation.REQUIRES_NEW)
public class ControlReadQueryService {
    private final JdbcTemplate jdbc;
    private final SupportService support;
    @PersistenceContext private EntityManager em;
    private Long tenant() {
        ControlIdentity actor=ControlIdentity.current();
        if(actor==null || actor.getAccessSessionId()!=null) throw new AccessDeniedException("需要独立总控身份");
        return TenantContext.requireTenantId();
    }
    /** Offset-bearing ISO timestamps, UTC half-open range; no receipt or presence writes. */
    public List<Map<String,Object>> conversations(Long user,Long admin,String createdFrom,String createdTo,int page){return conversations(user,admin,null,createdFrom,createdTo,page);}
    public List<Map<String,Object>> conversations(Long user,Long admin,String userEmail,String createdFrom,String createdTo,int page){
        Long owner=tenant();if(page<0||page>100000||(user!=null&&user<=0)||(admin!=null&&admin<=0))throw new IllegalArgumentException("筛选参数无效");
        boolean hasFrom=createdFrom!=null&&!createdFrom.isEmpty(),hasTo=createdTo!=null&&!createdTo.isEmpty();
        if(hasFrom!=hasTo)throw new IllegalArgumentException("请同时填写开始与结束时间，含时区偏移");
        String where=" WHERE x.tenant_id=?";List<Object> args=new ArrayList<>();args.add(owner);
        String pattern=com.gtcfesk.exchange.admin.AdminUserIdentity.emailPattern(userEmail);
        if(pattern!=null){where+=" AND LOWER(u.email) LIKE ? ESCAPE '!'";args.add(pattern);}
        if(user!=null){where+=" AND x.user_id=?";args.add(user);}if(admin!=null){where+=" AND x.admin_id=?";args.add(admin);}
        if(hasFrom){
            try{
                if(createdFrom.length()>40||createdTo.length()>40)throw new IllegalArgumentException("时间格式无效");
                Instant from=java.time.OffsetDateTime.parse(createdFrom).toInstant(),to=java.time.OffsetDateTime.parse(createdTo).toInstant();
                if(!from.isBefore(to)||java.time.Duration.between(from,to).compareTo(java.time.Duration.ofDays(366))>0)throw new IllegalArgumentException("时间范围须递增且不超过366天");
                where+=" AND x.created_at>=? AND x.created_at<?";
                args.add(java.time.LocalDateTime.ofInstant(from,java.time.ZoneOffset.UTC));args.add(java.time.LocalDateTime.ofInstant(to,java.time.ZoneOffset.UTC));
            }catch(java.time.DateTimeException e){throw new IllegalArgumentException("时间须为含时区偏移的ISO时间");}
        }
        args.add(page*30);return jdbc.queryForList("SELECT x.id,x.user_id,u.email AS user_email,u.remark AS user_remark,x.admin_id,x.status,x.created_at,x.closed_at,x.legal_hold FROM support_conversation x LEFT JOIN user_account u ON u.tenant_id=x.tenant_id AND u.id=x.user_id"+where+" ORDER BY x.created_at DESC,x.id DESC LIMIT 30 OFFSET ?",args.toArray());
    }
    public Map<String,Object> records(String kind,Long subject,String status,int page,int size) { return records(kind,subject,null,status,page,size); }
    public Map<String,Object> records(String kind,Long subject,String userEmail,String status,int page,int size) {
        Long tenant=tenant();
        if(page<1||page>100000||size<1||size>100||(subject!=null&&subject<=0))throw new IllegalArgumentException("筛选参数无效");
        String table,columns,owner,where=" WHERE x.tenant_id=?";
        switch(kind) {
            case "kyc":table="kyc_record";columns="id,user_id,real_name,id_number,status,review_remark,reviewed_by,reviewed_at,created_at,updated_at";owner="user_id";break;
            case "admins":table="admin_user";columns="id,account,email,role,enabled,must_change_password,created_at,updated_at";owner="id";break;
            case "agents":table="user_account";columns="id,email,remark,nickname,parent_user_id,status,created_at,last_login_at";owner="id";where+=" AND x.user_type='agent'";break;
            default:throw new IllegalArgumentException("未知监管分类");
        }
        String from=table+" x"+("kyc".equals(kind)?" LEFT JOIN user_account u ON u.tenant_id=x.tenant_id AND u.id=x.user_id":"");
        String pattern=com.gtcfesk.exchange.admin.AdminUserIdentity.emailPattern(userEmail);
        List<Object> args=new ArrayList<>();args.add(tenant);
        if(pattern!=null){where+=" AND LOWER("+("kyc".equals(kind)?"u":"x")+".email) LIKE ? ESCAPE '!'";args.add(pattern);}
        if(subject!=null){where+=" AND x."+owner+"=?";args.add(subject);}
        if(status!=null&&!status.isEmpty()) {
            if(status.length()>32||"admins".equals(kind))throw new IllegalArgumentException("状态筛选无效");
            where+=" AND x.status=?";args.add(status);
        }
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM "+from+where,Long.class,args.toArray());
        String select="x."+columns.replace(",",",x.");
        if("kyc".equals(kind)){select+=",u.email AS user_email,u.remark AS user_remark";columns+=",user_email,user_remark";}
        if("agents".equals(kind)){
            from+=" LEFT JOIN user_account parent ON parent.tenant_id=x.tenant_id AND parent.id=x.parent_user_id";
            select+=",parent.email AS parent_user_email,parent.remark AS parent_user_remark";columns+=",parent_user_email,parent_user_remark";
        }
        if("agents".equals(kind))select+=",(SELECT COUNT(*) FROM user_account child WHERE child.tenant_id=x.tenant_id AND child.parent_user_id=x.id) AS subordinate_count";
        args.add(size);args.add((page-1)*size);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT "+select+" FROM "+from+where+" ORDER BY x.id DESC LIMIT ? OFFSET ?",args.toArray());
        if("kyc".equals(kind))for(Map<String,Object> row:rows){String id=Objects.toString(row.get("id_number"),"");row.put("id_number",id.isEmpty()?"":"***"+id.substring(Math.max(0,id.length()-4)));}
        Map<String,Object> result=new LinkedHashMap<>();result.put("rows",rows);result.put("total",total);result.put("page",page);result.put("size",size);
        result.put("columns",(columns+("agents".equals(kind)?",subordinate_count":"")).split(","));return result;
    }
    public Map<String,Object> statistics() {
        Long t=tenant();Map<String,Object> out=new LinkedHashMap<>(),counts=new LinkedHashMap<>();
        counts.put("users",count("user_account",t,""));counts.put("agents",count("user_account",t," AND user_type='agent'"));
        counts.put("admins",count("admin_user",t,""));counts.put("pendingKyc",count("kyc_record",t," AND status='PENDING'"));
        counts.put("openSupport",count("support_conversation",t," AND status<>'CLOSED'"));
        out.put("tenantId",t);out.put("asOf",Instant.now().toString());out.put("counts",counts);
        out.put("assets",jdbc.queryForList("SELECT coin,COUNT(*) AS accounts,COALESCE(SUM(available),0) AS available,COALESCE(SUM(frozen),0) AS frozen FROM asset_account WHERE tenant_id=? GROUP BY coin ORDER BY coin",t));
        out.put("contracts",jdbc.queryForList("SELECT status,COUNT(*) AS orders FROM contract_order WHERE tenant_id=? GROUP BY status ORDER BY status",t));
        out.put("options",jdbc.queryForList("SELECT status,COUNT(*) AS orders FROM option_order WHERE tenant_id=? GROUP BY status ORDER BY status",t));
        out.put("deposits",jdbc.queryForList("SELECT currency,status,COUNT(*) AS records,COALESCE(SUM(amount),0) AS amount_usd FROM deposit_record WHERE tenant_id=? GROUP BY currency,status ORDER BY currency,status",t));
        out.put("withdrawals",jdbc.queryForList("SELECT currency,status,COUNT(*) AS records,COALESCE(SUM(amount),0) AS amount_usd FROM withdraw_record WHERE tenant_id=? GROUP BY currency,status ORDER BY currency,status",t));
        return out;
    }
    private long count(String table,Long tenant,String fixedCondition){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?"+fixedCondition,Long.class,tenant);}
    private SupportConversation conversation(long id) {
        tenant();if(id<=0)throw new IllegalArgumentException("会话编号无效");
        SupportConversation c=TenantEntities.find(em,SupportConversation.class,id);
        if(c==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在");return c;
    }
    public byte[] attachment(long conversation,long message) {
        conversation(conversation);
        SupportMessage m=TenantEntities.find(em,SupportMessage.class,message);
        if(m==null||!m.getConversationId().equals(conversation)||!m.isImage())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"附件不存在");
        byte[] bytes=bytes(m);
        if(!SupportService.sha256(bytes).equals(m.getImageHash())||!png(bytes))throw new ResponseStatusException(HttpStatus.CONFLICT,"附件完整性校验失败");
        return bytes;
    }
    private byte[] bytes(SupportMessage message) {
        SupportAttachment a=TenantEntities.find(em,SupportAttachment.class,message.getId());
        if(a==null||a.getContent()==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"附件不存在");
        if(a.getContent().length>10*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"单附件超过10MB");
        return a.getContent();
    }
    private static boolean png(byte[] b){return b.length>=8&&b[0]==(byte)137&&b[1]==80&&b[2]==78&&b[3]==71&&b[4]==13&&b[5]==10&&b[6]==26&&b[7]==10;}
    public Map<String,Object> evidence(long id) {
        SupportConversation c=conversation(id);
        List<SupportMessage> rows=em.createQuery("from SupportMessage where tenantId=:tenant and conversationId=:id order by id",SupportMessage.class)
            .setParameter("tenant",tenant()).setParameter("id",id).setMaxResults(2001).getResultList();
        if(rows.size()>2000)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"单次导出最多2000条，不截断；请POST /api/control/tenants/"+tenant()+"/support/conversations/"+id+"/archive-jobs并填写requestKey及reason");
        String previous="";boolean valid=true;long total=0;Map<String,String> images=new LinkedHashMap<>();
        for(SupportMessage m:rows){
            valid &= Objects.equals(previous,m.getPreviousHash())&&Objects.equals(m.getHash(),support.messageHash(m));previous=m.getHash();
            if(m.isImage()) {byte[] b=bytes(m);total+=b.length;if(total>32L*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"附件总量超过32MB；请POST /api/control/tenants/"+tenant()+"/support/conversations/"+id+"/archive-jobs并填写requestKey及reason");valid &= SupportService.sha256(b).equals(m.getImageHash())&&png(b);images.put(m.getId().toString(),Base64.getEncoder().encodeToString(b));}
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("version",1);out.put("tenantId",tenant());out.put("exportedAt",Instant.now().toString());
        out.put("exportedBy",ControlIdentity.actorId());out.put("exportedByType","CONTROL");out.put("conversation",c);out.put("messages",rows);out.put("imagesPngBase64",images);
        out.put("chainValid",valid&&Objects.equals(previous,c.getLastHash()));return out;
    }
}
