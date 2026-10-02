package com.gtcfesk.exchange.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.activity.ActivityCampaign;
import com.gtcfesk.exchange.activity.ActivityDelivery;
import com.gtcfesk.exchange.control.TenantPolicyService;
import com.gtcfesk.exchange.entity.Announcement;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantEntities;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import javax.persistence.*;
import java.time.*;
import java.util.*;

/** Read-through facade. No auto-delivery, money mutation, or copied public content. */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class UnifiedInboxService {
    private final SupportSettings settings;
    private final TenantPolicyService policy;
    private final ObjectMapper mapper;
    @PersistenceContext private EntityManager em;

    // Each UNION branch, including both sides of joins, is explicitly tenant scoped.
    private static final String ANNOUNCEMENTS = " from announcement a left join announcement_receipt r on r.tenant_id=a.tenant_id and r.user_id=:user and r.announcement_id=a.id where a.tenant_id=:tenant and a.status='PUBLISHED' and a.language=:language";
    private static final String ACTIVITIES = " from activity_delivery d join activity_campaign c on c.tenant_id=d.tenant_id and c.id=d.campaign_id where d.tenant_id=:tenant and d.user_id=:user and c.deleted=false and c.template=false and c.status in ('ACTIVE','PAUSED','CLOSED') and :activityEnabled=1";
    private static final String UNION = "select 'ANNOUNCEMENT' kind,a.id source_id,coalesce(a.display_at,a.created_at) display_at,r.read_at" + ANNOUNCEMENTS
        + " union all select 'LETTER',l.id,l.created_at,l.read_at from inbox_letter l where l.tenant_id=:tenant and l.user_id=:user and :lettersEnabled=1"
        + " union all select 'ACTIVITY',d.id,d.sent_at,d.opened_at" + ACTIVITIES;

    private long user() {
        TenantContext.requireTenantId();
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a.getAuthorities().stream().noneMatch(x -> "ROLE_USER".equals(x.getAuthority()))) throw new AccessDeniedException("仅限当前用户");
        try { return Long.parseLong(a.getName()); } catch (NumberFormatException e) { throw new AccessDeniedException("无效用户"); }
    }
    private String language(String requested) {
        String value = requested == null || requested.trim().isEmpty() ? "en" : requested.trim();
        if (value.length() > 10) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效语言");
        Long count = em.createQuery("select count(a) from Announcement a where a.tenantId=:tenant and a.status='PUBLISHED' and a.language=:language", Long.class)
            .setParameter("tenant", TenantContext.requireTenantId()).setParameter("language", value).getSingleResult();
        return count > 0 ? value : "en";
    }
    private Query query(String sql, long user, String language) {
        Query q = em.createNativeQuery(sql);
        q.setParameter("tenant", TenantContext.requireTenantId()).setParameter("user", user).setParameter("language", language);
        if (sql.contains(":lettersEnabled")) q.setParameter("lettersEnabled", settings.get().inboxEnabled ? 1 : 0);
        if (sql.contains(":activityEnabled")) q.setParameter("activityEnabled", policy.featureEnabled("activity") ? 1 : 0);
        return q;
    }
    public Map<String,Object> list(String requested, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效分页");
        long user = user(); String language = language(requested);
        Object[] counts = (Object[]) query("select count(*),coalesce(sum(case when read_at is null then 1 else 0 end),0) from ("+UNION+") inbox", user, language).getSingleResult();
        long total = ((Number)counts[0]).longValue();
        @SuppressWarnings("unchecked") List<Object[]> keys = query("select * from ("+UNION+") inbox order by display_at desc,kind,source_id desc", user, language).setFirstResult(page*size).setMaxResults(size).getResultList();
        List<Map<String,Object>> content = new ArrayList<>();
        for (Object[] key : keys) content.add(item(String.valueOf(key[0]), ((Number)key[1]).longValue(), key[3], requested));
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("content", content); out.put("number", page); out.put("size", size); out.put("totalElements", total);
        out.put("totalPages", (total+size-1)/size); out.put("unread", ((Number)counts[1]).longValue());
        return out;
    }
    private Object[] visible(String key, long user, String requested) {
        if (key == null || !key.matches("(ANNOUNCEMENT|LETTER|ACTIVITY):[1-9][0-9]{0,18}")) throw missing();
        String[] parts = key.split(":"); long id;
        try { id=Long.parseLong(parts[1]); } catch (NumberFormatException e) { throw missing(); }
        List<?> rows = query("select * from ("+UNION+") inbox where kind=:kind and source_id=:id", user, language(requested))
            .setParameter("kind", parts[0]).setParameter("id", id).getResultList();
        if (rows.isEmpty()) throw missing();
        return (Object[]) rows.get(0);
    }
    public Map<String,Object> detail(String key, String requested) {
        Object[] row=visible(key,user(),requested);
        return item(String.valueOf(row[0]),((Number)row[1]).longValue(),row[3],requested);
    }
    private Map<String,Object> item(String kind, long id, Object readAt, String requested) {
        Map<String,Object> out=new LinkedHashMap<>(); out.put("id",kind+":"+id); out.put("type",kind); out.put("sourceId",id); out.put("readAt",instant(readAt));
        if ("ANNOUNCEMENT".equals(kind)) {
            Announcement a=TenantEntities.find(em,Announcement.class,id);
            out.put("title",a.getTitle()); out.put("content",a.getContent()); out.put("displayAt",instant(a.getDisplayAt())); out.put("createdAt",instant(a.getCreatedAt()));
            out.put("countdownSeconds",a.getCountdownSeconds()); out.put("language",a.getLanguage());
        } else if ("LETTER".equals(kind)) {
            InboxLetter l=TenantEntities.find(em,InboxLetter.class,id);
            out.put("title",l.getTitle()); out.put("content",l.getContent()); out.put("displayAt",instant(l.getCreatedAt())); out.put("createdAt",instant(l.getCreatedAt()));
        } else {
            ActivityDelivery d=TenantEntities.find(em,ActivityDelivery.class,id);
            ActivityCampaign c=TenantEntities.find(em,ActivityCampaign.class,d.getCampaignId());
            JsonNode copy;
            try { JsonNode all=mapper.readTree(c.getTranslations()); copy=all.path(requested==null?"en":requested); if(!copy.isObject())copy=all.path(c.getDefaultLocale()); if(!copy.isObject())copy=all.path("en"); if(!copy.isObject() && all.size()>0)copy=all.elements().next(); }
            catch(Exception e){copy=mapper.createObjectNode();}
            String amount=c.getAmount().stripTrailingZeros().toPlainString();
            String days=c.getClaimValidityDays()==null?(requested!=null && requested.startsWith("zh")?"不设置到期":"No expiry"):String.valueOf(c.getClaimValidityDays());
            out.put("title",copy.path("title").asText("Activity").replace("{amount}",amount).replace("{days}",days));
            out.put("content",copy.path("body").asText("").replace("{amount}",amount).replace("{days}",days));
            out.put("displayAt",instant(d.getSentAt())); out.put("createdAt",instant(d.getSentAt())); out.put("active",c.active()); out.put("claimedAt",instant(d.getClaimedAt()));
            out.put("activityId",d.getId());
        }
        return out;
    }
    private static String instant(Object value) {
        if(value==null)return null;
        if(value instanceof LocalDateTime)return ((LocalDateTime)value).toInstant(ZoneOffset.UTC).toString();
        if(value instanceof java.sql.Timestamp)return ((java.sql.Timestamp)value).toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
        if(value instanceof Instant)return value.toString();
        throw new IllegalArgumentException("Unexpected timestamp type: "+value.getClass());
    }
    private void lockUser(long user) {
        if(TenantEntities.find(em,UserAccount.class,user,LockModeType.PESSIMISTIC_WRITE)==null)throw new AccessDeniedException("用户不存在");
    }
    @Transactional public Map<String,Object> read(String key, String requested) {
        long user=user(); lockUser(user); Object[] row=visible(key,user,requested);
        String kind=String.valueOf(row[0]); long id=((Number)row[1]).longValue();
        if(row[3]==null) {
            if("ANNOUNCEMENT".equals(kind)) {
                AnnouncementReceipt r=new AnnouncementReceipt(); r.setUserId(user); r.setAnnouncementId(id); r.setReadAt(LocalDateTime.now(ZoneOffset.UTC)); em.persist(r);
            } else if("LETTER".equals(kind)) {
                InboxLetter l=TenantEntities.find(em,InboxLetter.class,id,LockModeType.PESSIMISTIC_WRITE);
                if(!Objects.equals(l.getUserId(),user))throw missing(); l.setReadAt(Instant.now());
            } else {
                ActivityDelivery d=TenantEntities.find(em,ActivityDelivery.class,id,LockModeType.PESSIMISTIC_WRITE);
                if(!Objects.equals(d.getUserId(),user))throw missing();
                if(d.getOpenedAt()==null){LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC); d.setOpenedAt(now); if(d.getReceivedAt()==null)d.setReceivedAt(now); d.setOpenCount(d.getOpenCount()+1);}
            }
            em.flush();
        }
        return detail(key,requested);
    }
    @Transactional public void readAll(String requested) {
        long user=user(); lockUser(user); String language=language(requested);
        // User lock serializes receipt inserts; the unique key also protects concurrent requests.
        query("insert into announcement_receipt(tenant_id,user_id,announcement_id,read_at) select :tenant,:user,a.id,:now"+ANNOUNCEMENTS+" and r.id is null",user,language)
            .setParameter("now",LocalDateTime.now(ZoneOffset.UTC)).executeUpdate();
        if(settings.get().inboxEnabled) em.createQuery("update InboxLetter set readAt=:now where tenantId=:tenant and userId=:user and readAt is null")
            .setParameter("tenant",TenantContext.requireTenantId()).setParameter("user",user).setParameter("now",Instant.now()).executeUpdate();
        if(policy.featureEnabled("activity")) em.createQuery("update ActivityDelivery d set d.openedAt=:now,d.receivedAt=coalesce(d.receivedAt,:now),d.openCount=d.openCount+1,d.rowVersion=d.rowVersion+1 where d.tenantId=:tenant and d.userId=:user and d.openedAt is null and d.campaignId in (select c.id from ActivityCampaign c where c.tenantId=:tenant and c.deleted=false and c.template=false and c.status in ('ACTIVE','PAUSED','CLOSED'))")
            .setParameter("tenant",TenantContext.requireTenantId()).setParameter("user",user).setParameter("now",LocalDateTime.now(ZoneOffset.UTC)).executeUpdate();
        em.clear();
    }
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"消息不存在");}
}
