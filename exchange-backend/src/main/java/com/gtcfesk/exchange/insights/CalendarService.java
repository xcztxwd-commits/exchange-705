package com.gtcfesk.exchange.insights;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.tenant.*;
import com.gtcfesk.exchange.support.*;
import com.gtcfesk.exchange.simulation.SimulationEnvironment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class CalendarService {
    @PersistenceContext private EntityManager em;
    private final ObjectMapper json;
    private final SimulationEnvironment simulation;
    private final SupportSettings support;
    private final com.gtcfesk.exchange.control.TenantPolicyService policy;
    public String environment(){return simulation.enabled()?"DEMO":"REAL";}
    private <T> TypedQuery<T> scoped(String jpql,Class<T> type){return em.createQuery(jpql,type).setParameter("tenant",TenantContext.requireTenantId()).setParameter("env",environment());}
    private CalendarEvent event(String id,boolean admin,boolean lock){
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw missing();
        TypedQuery<CalendarEvent> q=scoped("from CalendarEvent e where e.tenantId=:tenant and e.environment=:env and e.eventId=:id"+(admin?"":" and e.published=true"),CalendarEvent.class).setParameter("id",id);
        if(lock)q.setLockMode(LockModeType.PESSIMISTIC_WRITE);List<CalendarEvent> rows=q.setMaxResults(1).getResultList();if(rows.isEmpty())throw missing();return rows.get(0);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("日历编码失败",e);}}
    private CalendarData decode(CalendarEvent e){try{return json.readValue(e.getDataJson(),CalendarData.class);}catch(Exception ex){throw new IllegalStateException("日历记录不可解析",ex);}}
    private String hash(CalendarData d){try{JsonNode node=json.valueToTree(d);((com.fasterxml.jackson.databind.node.ObjectNode)node).remove("sourceAsOf");byte[] bytes=MessageDigest.getInstance("SHA-256").digest(node.toString().getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> view(CalendarEvent e){
        CalendarData d=decode(e);Map<String,Object> out=json.convertValue(d,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        if("SCHEDULED".equals(d.status)&&d.releaseAt!=null&&d.releaseAt.isBefore(Instant.now()))out.put("status","AWAITING_RELEASE");
        out.put("rowVersion",e.getRowVersion());out.put("published",e.isPublished());out.put("manualLock",e.isManualLock());out.put("updatedAt",e.getUpdatedAt());out.put("environment",e.getEnvironment());return out;
    }
    public Map<String,Object> detail(String id,boolean admin){return view(event(id,admin,false));}
    public Map<String,Object> list(LocalDate from,LocalDate to,String country,String importance,String metric,String status,int page,int size,boolean admin){
        if(from==null)from=LocalDate.now(ZoneOffset.UTC);if(to==null)to=from.plusDays(30);
        if(to.isBefore(from)||java.time.temporal.ChronoUnit.DAYS.between(from,to)>365||page<0||page>10000||size<1||size>100)throw CalendarData.bad("日期窗口最多366日，size须为1至100");
        if(metric!=null&&!CalendarData.METRICS.contains(metric))throw CalendarData.bad("不支持的事件类型");if(status!=null&&!CalendarData.STATES.contains(status))throw CalendarData.bad("事件状态无效");
        boolean unsupported=country!=null&&!country.equals("US")||importance!=null&&!importance.equals("HIGH");
        Instant now=Instant.now();boolean waiting="AWAITING_RELEASE".equals(status),scheduled="SCHEDULED".equals(status);
        String state=waiting?" and (e.status='AWAITING_RELEASE' or (e.status='SCHEDULED' and e.releaseAt<=:now))":scheduled?" and e.status='SCHEDULED' and (e.releaseAt is null or e.releaseAt>:now)":status==null?"":" and e.status=:status";
        String where=" where e.tenantId=:tenant and e.environment=:env and e.releaseDate>=:from and e.releaseDate<=:to"+(admin?"":" and e.published=true")+(metric==null?"":" and e.metric=:metric")+state;
        TypedQuery<CalendarEvent> q=scoped("from CalendarEvent e"+where+" order by e.releaseDate,e.releaseAt,e.eventId",CalendarEvent.class).setParameter("from",from).setParameter("to",to);
        TypedQuery<Long> count=scoped("select count(e) from CalendarEvent e"+where,Long.class).setParameter("from",from).setParameter("to",to);if(metric!=null){q.setParameter("metric",metric);count.setParameter("metric",metric);}if(waiting||scheduled){q.setParameter("now",now);count.setParameter("now",now);}else if(status!=null){q.setParameter("status",status);count.setParameter("status",status);}
        long total=unsupported?0:count.getSingleResult();List<Map<String,Object>> content=new ArrayList<>();if(!unsupported)for(CalendarEvent e:q.setFirstResult(page*size).setMaxResults(size).getResultList())content.add(view(e));
        Map<String,Object> out=new LinkedHashMap<>();out.put("content",content);out.put("number",page);out.put("size",size);out.put("totalElements",total);out.put("totalPages",(total+size-1)/size);out.put("from",from);out.put("to",to);out.put("asOf",Instant.now());out.put("environment",environment());
        Map<String,Object> coverage=new LinkedHashMap<>();coverage.put("countries",Collections.singletonList("US"));coverage.put("importance",Collections.singletonList("HIGH"));coverage.put("metrics",CalendarData.METRICS);coverage.put("forecast","NOT_PROVIDED");coverage.put("scope","Verified official schedules and materials; not global coverage or historical initial vintages");
        List<?> actual=scoped("select e.metric,count(e),min(e.releaseDate),max(e.releaseDate) from CalendarEvent e where e.tenantId=:tenant and e.environment=:env and e.published=true group by e.metric",Object[].class).getResultList();coverage.put("persistedCoverage",actual);out.put("coverage",coverage);return out;
    }
    private void write(CalendarEvent e,CalendarData d){d.validate();e.setMetric(d.metric);e.setReleaseDate(d.releaseDate);e.setReleaseAt(d.releaseAt);e.setStatus(d.status);e.setDataJson(encode(d));e.setUpdatedAt(Instant.now());}
    private void audit(CalendarEvent e,String actor,String action,String reason,String before){CalendarAudit a=new CalendarAudit();a.setEnvironment(environment());a.setEventId(e.getEventId());a.setActor(actor);a.setAction(action);a.setReason(reason);a.setBeforeJson(before);a.setAfterJson(encode(view(e)));em.persist(a);}
    public static String actor(){Authentication a=SecurityContextHolder.getContext().getAuthentication();if(a==null||a.getAuthorities().stream().noneMatch(r->r.getAuthority().equals("ROLE_ADMIN")||r.getAuthority().equals("ROLE_SUPER_ADMIN")))throw new AccessDeniedException("仅限授权管理员");return "ADMIN:"+a.getName();}
    private static void reason(String s){if(s==null||s.trim().isEmpty()||s.length()>1000)throw CalendarData.bad("必须填写核验/修改依据（最多1000字）");}
    private void version(CalendarEvent e,Long version){if(version==null||e.getRowVersion()!=version)throw new ResponseStatusException(HttpStatus.CONFLICT,"日历记录已更新，请刷新后重试");}
    @Transactional public int importBatch(CalendarParser.Batch batch,boolean manual,String material){
        if(batch.events.size()>2000||batch.observations.size()>1000)throw CalendarData.bad("导入条目超过上限");String who=manual?actor():"SOURCE_SYNC";if(manual)reason(material);int changed=0;
        for(CalendarData d:batch.events){d.validate();changed+=upsert(d,manual,who,manual?material:"官方日程/公告更新",false);}
        for(CalendarData d:batch.observations){d.validate();changed+=upsert(d,manual,who,manual?material:"当前官方时间序列，不代表历史初值",true);}
        em.flush();return changed;
    }
    private int upsert(CalendarData incoming,boolean manual,String who,String reason,boolean observation){
        List<CalendarEvent> rows=scoped("from CalendarEvent e where e.tenantId=:tenant and e.environment=:env and e.eventId=:id",CalendarEvent.class).setParameter("id",incoming.eventId).setLockMode(LockModeType.PESSIMISTIC_WRITE).setMaxResults(1).getResultList();
        if(rows.isEmpty()&&observation)return 0;CalendarEvent e=rows.isEmpty()?new CalendarEvent():rows.get(0);String before=rows.isEmpty()?null:encode(view(e)),incomingHash=hash(incoming);
        if(!manual&&e.isManualLock()){String key=String.valueOf(incoming.sourceChannel)+":";String marker=key+incomingHash+";",saved=e.getUpstreamHash();if(saved==null||!saved.contains(marker)){String rest=saved==null?"":saved.replaceAll(java.util.regex.Pattern.quote(key)+"[0-9a-f]{64};","");if(rest.matches("[0-9a-f]{64}"))rest="";e.setUpstreamHash(rest+marker);audit(e,who,"SKIPPED_MANUAL_LOCK",reason+"；保留核验人工覆盖",before);}return 0;}
        CalendarData data=incoming;
        if(!rows.isEmpty()&&!manual){CalendarData old=decode(e);data=json.convertValue(old,CalendarData.class);
            boolean confirmedRelease="RELEASED".equals(old.status)&&incoming.sourceChannel!=null&&incoming.sourceChannel.endsWith("_CALENDAR");
            if(!observation&&!confirmedRelease){data.title=incoming.title;data.sourceUid=incoming.sourceUid;data.sourceRevision=incoming.sourceRevision;
                if(!confirmedRelease&&(incoming.releaseAt!=null||!"UNKNOWN".equals(incoming.timePrecision))){if(!Objects.equals(old.releaseAt,incoming.releaseAt)||!Objects.equals(old.releaseDate,incoming.releaseDate)){if(!"RELEASED".equals(incoming.status)&&!"RELEASED".equals(old.status))data.status="POSTPONED";}data.releaseAt=incoming.releaseAt;data.releaseDate=incoming.releaseDate;data.timePrecision=incoming.timePrecision;data.sourceTimezone=incoming.sourceTimezone;}}
            if(incoming.actual!=null){if(old.actual!=null&&new BigDecimal(old.actual).compareTo(new BigDecimal(incoming.actual))!=0)data.isRevised=true;data.actual=incoming.actual;data.previous=incoming.previous;data.actualBasis=incoming.actualBasis;data.footnotes=incoming.footnotes;}
            if("RELEASED".equals(incoming.status)||"CANCELLED".equals(incoming.status))data.status=incoming.status;
            if(!confirmedRelease&&(data.actual==null||incoming.actual!=null||incoming.metric.startsWith("FOMC"))){data.sourceUrl=incoming.sourceUrl;data.sourceChannel=incoming.sourceChannel;data.sourceAsOf=incoming.sourceAsOf;}
            data.isRevised=data.isRevised||incoming.isRevised;
            if(hash(data).equals(hash(old)))return 0;
        }
        if(rows.isEmpty()){e.setEnvironment(environment());e.setEventId(incoming.eventId);e.setPublished(!manual);e.setUpstreamHash(incomingHash);}
        e.setManualLock(manual);write(e,data);if(rows.isEmpty())em.persist(e);audit(e,who,manual?"VERIFIED_IMPORT":"SOURCE_UPDATE",reason,before);return 1;
    }
    public static class Edit {public Long rowVersion;public String reason;public CalendarData data;public Boolean manualLock=true;}
    @Transactional public Map<String,Object> edit(String id,Edit input){
        if(input==null||input.data==null||input.manualLock==null)throw CalendarData.bad("缺少核验数据");reason(input.reason);CalendarEvent e=event(id,true,true);version(e,input.rowVersion);input.data.actualBasis=(input.data.actual!=null||input.data.previous!=null)?"OFFICIAL_MATERIAL":input.data.actualBasis;input.data.sourceAsOf=Instant.now();input.data.sourceChannel="VERIFIED_OFFICIAL_MATERIAL";input.data.validate();if(!id.equals(input.data.eventId))throw CalendarData.bad("不可更改统计身份，请另建事件");String before=encode(view(e));e.setManualLock(input.manualLock);write(e,input.data);audit(e,actor(),"ADMIN_CORRECTION",input.reason,before);em.flush();return view(e);
    }
    public static class Publication {public Long rowVersion;public Boolean published;public String status,reason;}
    @Transactional public Map<String,Object> publish(String id,Publication input){if(input==null)throw CalendarData.bad("缺少发布字段");reason(input.reason);if(input.published==null||!CalendarData.STATES.contains(input.status))throw CalendarData.bad("发布字段无效");CalendarEvent e=event(id,true,true);version(e,input.rowVersion);String before=encode(view(e));CalendarData d=decode(e);d.status=input.status;e.setPublished(input.published);e.setManualLock(true);write(e,d);audit(e,actor(),"PUBLICATION",input.reason,before);em.flush();return view(e);}
    public List<CalendarAudit> audits(String id,int page){event(id,true,false);if(page<0||page>10000)throw CalendarData.bad("无效分页");return scoped("from CalendarAudit a where a.tenantId=:tenant and a.environment=:env and a.eventId=:id order by a.id desc",CalendarAudit.class).setParameter("id",id).setFirstResult(page*50).setMaxResults(50).getResultList();}
    private long user(){Authentication a=SecurityContextHolder.getContext().getAuthentication();if(a==null||!a.isAuthenticated()||a.getAuthorities().stream().noneMatch(r->r.getAuthority().equals("ROLE_USER")))throw new AccessDeniedException("仅限当前用户");try{long id=Long.parseLong(a.getName());if(TenantEntities.find(em,UserAccount.class,id)==null)throw new AccessDeniedException("用户不存在");return id;}catch(NumberFormatException ex){throw new AccessDeniedException("无效用户");}}
    private void inbox(){policy.requireNewBusiness("inbox");if(!support.get().inboxEnabled)throw new ResponseStatusException(HttpStatus.CONFLICT,"站内信已关闭，不能设置提醒");}
    private static void lead(int lead){if(!Arrays.asList(5,15,30,60,1440).contains(lead))throw CalendarData.bad("提前量须为5/15/30/60/1440分钟");}
    public static class ReminderInput {public int leadMinutes=15;public String timezone="Asia/Singapore";}
    private List<CalendarReminder> reminders(String id,long user,boolean lock){TypedQuery<CalendarReminder> q=scoped("from CalendarReminder r where r.tenantId=:tenant and r.environment=:env and r.userId=:user"+(id==null?"":" and r.eventId=:event")+" order by r.id",CalendarReminder.class).setParameter("user",user);if(id!=null)q.setParameter("event",id);if(lock)q.setLockMode(LockModeType.PESSIMISTIC_WRITE);return q.setMaxResults(1000).getResultList();}
    private Map<String,Object> reminderView(CalendarReminder r){Map<String,Object> out=new LinkedHashMap<>();out.put("eventId",r.getEventId());out.put("leadMinutes",r.getLeadMinutes());out.put("timezone",r.getTimezone());out.put("enabled",r.isEnabled());out.put("deliveredAt",r.getDeliveredAt());out.put("deliveredReleaseAt",r.getDeliveredReleaseAt());out.put("letterId",r.getLetterId());String state=r.getDeliveredAt()!=null?"DELIVERED":r.isEnabled()?"PENDING":"CANCELLED";
        try{CalendarEvent e=event(r.getEventId(),true,false);CalendarData d=decode(e);out.put("eventStatus",d.status);out.put("releaseAt",d.releaseAt);out.put("releaseDate",d.releaseDate);out.put("timePrecision",d.timePrecision);if(state.equals("PENDING")){if(!e.isPublished())state="UNPUBLISHED";else if(d.status.equals("CANCELLED"))state="EVENT_CANCELLED";else if(!d.timePrecision.equals("MINUTE"))state="TIME_UNCONFIRMED";else if(d.releaseAt==null||!d.releaseAt.isAfter(Instant.now())||d.status.equals("RELEASED"))state="EXPIRED";}}
        catch(ResponseStatusException ex){if(state.equals("PENDING"))state="EVENT_UNAVAILABLE";}out.put("state",state);out.put("environment",r.getEnvironment());return out;}
    public List<Map<String,Object>> reminders(String eventId){long user=user();List<Map<String,Object>> out=new ArrayList<>();for(CalendarReminder r:reminders(eventId,user,false))out.add(reminderView(r));return out;}
    @Transactional public Map<String,Object> setReminder(String id,ReminderInput input){
        if(input==null)throw CalendarData.bad("缺少提醒参数");lead(input.leadMinutes);if(input.timezone==null||input.timezone.length()>64||!ZoneId.getAvailableZoneIds().contains(input.timezone))throw CalendarData.bad("须使用有效 IANA 时区");inbox();CalendarEvent e=event(id,false,true);CalendarData d=decode(e);
        if(!"MINUTE".equals(d.timePrecision)||d.releaseAt==null||!d.releaseAt.isAfter(Instant.now())||Arrays.asList("CANCELLED","RELEASED").contains(d.status))throw new ResponseStatusException(HttpStatus.CONFLICT,"事件无确认未来时刻或已取消/公布");long user=user();TenantEntities.find(em,UserAccount.class,user,LockModeType.PESSIMISTIC_WRITE);
        List<CalendarReminder> existing=reminders(id,user,true);CalendarReminder r=existing.stream().filter(x->x.getLeadMinutes()==input.leadMinutes).findFirst().orElse(null);boolean fresh=r==null;if(fresh){if(reminders(null,user,false).size()>=500)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"提醒数量超过上限");r=new CalendarReminder();r.setUserId(user);r.setEnvironment(environment());r.setEventId(id);r.setLeadMinutes(input.leadMinutes);}
        r.setEnabled(true);r.setTimezone(input.timezone);r.setUpdatedAt(Instant.now());if(fresh)em.persist(r);em.flush();return reminderView(r);
    }
    @Transactional public void cancelReminder(String id,int minutes){lead(minutes);event(id,true,true);long user=user();TenantEntities.find(em,UserAccount.class,user,LockModeType.PESSIMISTIC_WRITE);for(CalendarReminder r:reminders(id,user,true))if(r.getLeadMinutes()==minutes){r.setEnabled(false);r.setUpdatedAt(Instant.now());}}
    @Transactional public int dispatchDue(Instant now){
        // Setting consent is new business and uses the live authorization guard. Delivering
        // previously committed consent is a background job, not an HTTP/session impersonation.
        if(!support.get().inboxEnabled||!"ACTIVE".equals(policy.current().getStatus()))return 0;int sent=0;
        List<CalendarEvent> due=scoped("from CalendarEvent e where e.tenantId=:tenant and e.environment=:env and e.published=true and e.status not in ('CANCELLED','RELEASED') and e.releaseAt>:now and e.releaseAt<=:end and exists (select r.id from CalendarReminder r where r.tenantId=:tenant and r.environment=:env and r.eventId=e.eventId and r.enabled=true and r.deliveredAt is null) order by e.releaseAt,e.eventId",CalendarEvent.class).setParameter("now",now).setParameter("end",now.plusSeconds(86400)).setMaxResults(100).getResultList();
        // ponytail: bounded tenant batch transaction; split per-event only if reminder volume exceeds this ceiling.
        for(CalendarEvent candidate:due){CalendarEvent e=event(candidate.getEventId(),false,true);CalendarData d=decode(e);if(d.releaseAt==null||!d.releaseAt.isAfter(now)||!"MINUTE".equals(d.timePrecision)||Arrays.asList("CANCELLED","RELEASED").contains(d.status))continue;
            List<CalendarReminder> rows=scoped("from CalendarReminder r where r.tenantId=:tenant and r.environment=:env and r.eventId=:event and r.enabled=true and r.deliveredAt is null order by r.id",CalendarReminder.class).setParameter("event",d.eventId).setLockMode(LockModeType.PESSIMISTIC_WRITE).setMaxResults(200).getResultList();
            for(CalendarReminder r:rows){if(now.isBefore(d.releaseAt.minusSeconds(r.getLeadMinutes()*60L)))continue;UserAccount recipient=TenantEntities.find(em,UserAccount.class,r.getUserId(),LockModeType.PESSIMISTIC_WRITE);if(recipient==null||!Arrays.asList("normal","active").contains(recipient.getStatus()))continue;
                InboxLetter letter=new InboxLetter();letter.setUserId(r.getUserId());letter.setRequestId("CAL:"+environment()+":"+d.eventId+":"+r.getLeadMinutes());letter.setTitle("财经事件提醒 · "+d.title.substring(0,Math.min(d.title.length(),80)));letter.setContent(d.title+"\n发布时间："+d.releaseAt.atZone(ZoneId.of(r.getTimezone())).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))+"\nUTC："+d.releaseAt+"\n来源："+d.agency+" / "+d.sourceUrl+"\n事件ID："+d.eventId+"\n站内提醒，不是交易建议；发布时间可能调整。" );letter.setCreatedAt(now);em.persist(letter);em.flush();r.setDeliveredAt(now);r.setDeliveredReleaseAt(d.releaseAt);r.setLetterId(letter.getId());r.setUpdatedAt(now);sent++;
            }
        }
        em.flush();return sent;
    }
    public String ics(String id){CalendarData d=decode(event(id,false,false));if(d.releaseDate==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"日期未确认");String start="MINUTE".equals(d.timePrecision)?"DTSTART:"+DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(d.releaseAt):"DTSTART;VALUE=DATE:"+d.releaseDate.format(DateTimeFormatter.BASIC_ISO_DATE);return foldIcs("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Exchange//Official calendar//ZH\r\nBEGIN:VEVENT\r\nUID:"+d.eventId+"@exchange-calendar\r\nDTSTAMP:"+DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())+"\r\n"+start+"\r\nSUMMARY:"+d.title.replace("\\","\\\\").replace(",","\\,").replace(";","\\;")+"\r\nURL:"+d.sourceUrl+"\r\nSTATUS:"+("CANCELLED".equals(d.status)?"CANCELLED":"DATE".equals(d.timePrecision)?"TENTATIVE":"CONFIRMED")+"\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");}
    static String foldIcs(String raw){StringBuilder out=new StringBuilder();for(String line:raw.split("\r\n")){int bytes=0;for(int i=0;i<line.length();){int cp=line.codePointAt(i);String character=new String(Character.toChars(cp));int length=character.getBytes(StandardCharsets.UTF_8).length;if(bytes+length>75){out.append("\r\n ");bytes=1;}out.append(character);bytes+=length;i+=Character.charCount(cp);}out.append("\r\n");}return out.toString();}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"事件不存在或未发布");}
}
