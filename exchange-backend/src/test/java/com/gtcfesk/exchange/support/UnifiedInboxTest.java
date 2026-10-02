package com.gtcfesk.exchange.support;

import com.gtcfesk.exchange.activity.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.persistence.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(UnifiedInboxFixture.class)
public class UnifiedInboxTest {
    static {TimeZone.setDefault(TimeZone.getTimeZone("UTC")); ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);}
    @Autowired UnifiedInboxService service;
    @Autowired PlatformTransactionManager manager;
    @Autowired SupportSettings.Settings config;
    @PersistenceContext EntityManager em;
    long tenant, otherTenant, a, b, c, announcement, letter, activity, draft, hidden, draftActivity;
    final LocalDateTime epoch=LocalDateTime.of(2026,9,1,0,0);
    @BeforeEach void seed(){
        config.inboxEnabled=true; tenant=Math.abs(UUID.randomUUID().getMostSignificantBits() % 1000000)+100;otherTenant=tenant+1000001;
        in(tenant,()->new TransactionTemplate(manager).execute(s->{
            a=user();b=user();
            announcement=announcement("Published","PUBLISHED","en",epoch.plusDays(3));
            draft=announcement("SECRET DRAFT","DRAFT","en",epoch.plusDays(8));hidden=announcement("SECRET HIDDEN","HIDDEN","en",epoch.plusDays(7));
            letter=letter(a,"Private A",epoch.plusDays(2));letter(b,"Private B",epoch.plusDays(9));
            activity=activity(a,"ACTIVE");draftActivity=activity(a,"DRAFT");
            return null;
        }));
        in(otherTenant,()->new TransactionTemplate(manager).execute(s->{c=user();letter(c,"Other tenant",epoch.plusDays(10));announcement("Other tenant public","PUBLISHED","en",epoch.plusDays(10));return null;}));
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();TenantContext.clear();}
    void in(long id,Runnable action){try(TenantContext.Scope ignored=TenantContext.open(id)){action.run();}}
    public static void auth(long id){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(String.valueOf(id),null,Collections.singleton(new SimpleGrantedAuthority("ROLE_USER"))));}
    long user(){UserAccount u=new UserAccount();u.setEmail(UUID.randomUUID()+"@t04.invalid");u.setPasswordHash("synthetic");em.persist(u);return u.getId();}
    long announcement(String title,String status,String language,LocalDateTime at){Announcement x=new Announcement();x.setTitle(title);x.setContent(title+" body");x.setStatus(status);x.setLanguage(language);x.setDisplayAt(at);em.persist(x);return x.getId();}
    long letter(long user,String title,LocalDateTime at){InboxLetter l=new InboxLetter();l.setUserId(user);l.setAdminId(1L);l.setRequestId(UUID.randomUUID().toString());l.setTitle(title);l.setContent(title+" body");l.setCreatedAt(at.toInstant(ZoneOffset.UTC));em.persist(l);return l.getId();}
    long activity(long user,String status){ActivityCampaign campaign=new ActivityCampaign();campaign.setName("Campaign");campaign.setStatus(status);campaign.setDefaultLocale("ja");campaign.setTranslations("{\"en\":{\"title\":\"Reward {amount}\",\"body\":\"Activity body\"},\"ja\":{\"title\":\"特典\",\"body\":\"活動本文\"}}");em.persist(campaign);ActivityDelivery d=new ActivityDelivery();d.setCampaignId(campaign.getId());d.setUserId(user);d.setSentAt(epoch.plusDays(1));em.persist(d);return d.getId();}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> page){return (List<Map<String,Object>>)page.get("content");}
    @Test void mixedPaginationStableIdsFallbackAndNoImplicitDelivery(){in(tenant,()->{
        auth(a);Map<String,Object> first=service.list("en",0,2);assertEquals(3L,first.get("totalElements"));assertEquals(3L,first.get("unread"));assertEquals(2L,first.get("totalPages"));
        assertEquals("ANNOUNCEMENT:"+announcement,rows(first).get(0).get("id"));assertEquals("LETTER:"+letter,rows(first).get(1).get("id"));
        Map<String,Object> second=service.list("en",1,2);assertEquals(1,rows(second).size());assertEquals("ACTIVITY:"+activity,rows(second).get(0).get("id"));
        assertTrue(rows(service.list("en",2,2)).isEmpty());assertEquals(3L,service.list("fr",0,30).get("unread"));
        assertEquals("特典",service.detail("ACTIVITY:"+activity,"fr").get("title"));
        assertEquals("2026-09-04T00:00:00Z",rows(first).get(0).get("displayAt"));
        assertThrows(ResponseStatusException.class,()->service.detail("ANNOUNCEMENT:"+draft,"en"));
        assertThrows(ResponseStatusException.class,()->service.detail("ANNOUNCEMENT:"+hidden,"en"));
        assertThrows(ResponseStatusException.class,()->service.detail("ACTIVITY:"+draftActivity,"en"));
        assertThrows(ResponseStatusException.class,()->service.list("en",-1,30));assertThrows(ResponseStatusException.class,()->service.list("en",0,101));
        assertEquals(3L,service.list("en",0,30).get("unread"));
    });}
    @Test void activityCopyUsesClaimValidityRatherThanLoginFilter(){in(tenant,()->{
        auth(a);
        new TransactionTemplate(manager).execute(s->{
            ActivityDelivery d=em.find(ActivityDelivery.class,activity);
            ActivityCampaign c=em.find(ActivityCampaign.class,d.getCampaignId());
            c.setRecentLoginDays(0);c.setClaimValidityDays(3);
            c.setTranslations("{\"en\":{\"title\":\"Reward {days}\",\"body\":\"Valid {days}\"},\"zh-TW\":{\"title\":\"有效 {days}\",\"body\":\"期限 {days}\"}}");
            return null;
        });
        assertEquals("Reward 3",service.detail("ACTIVITY:"+activity,"en").get("title"));
        assertEquals("Valid 3",service.detail("ACTIVITY:"+activity,"en").get("content"));
        new TransactionTemplate(manager).execute(s->{em.find(ActivityCampaign.class,em.find(ActivityDelivery.class,activity).getCampaignId()).setClaimValidityDays(null);return null;});
        assertEquals("Valid No expiry",service.detail("ACTIVITY:"+activity,"en").get("content"));
        assertEquals("期限 不设置到期",service.detail("ACTIVITY:"+activity,"zh-TW").get("content"));
    });}
    @Test void receiptsArePerUserAndReuseLegacyReadColumns(){in(tenant,()->{
        auth(a);Map<String,Object> read=service.read("ANNOUNCEMENT:"+announcement,"en");assertNotNull(read.get("readAt"));assertEquals(read.get("readAt"),service.read("ANNOUNCEMENT:"+announcement,"en").get("readAt"));
        auth(b);assertNull(service.detail("ANNOUNCEMENT:"+announcement,"en").get("readAt"));assertThrows(ResponseStatusException.class,()->service.read("LETTER:"+letter,"en"));assertThrows(ResponseStatusException.class,()->service.read("ACTIVITY:"+activity,"en"));
        auth(a);service.read("LETTER:"+letter,"en");service.read("ACTIVITY:"+activity,"en");assertEquals(0L,service.list("en",0,30).get("unread"));
        new TransactionTemplate(manager).execute(s->{assertNotNull(em.createQuery("from InboxLetter where tenantId=:t and id=:id",InboxLetter.class).setParameter("t",tenant).setParameter("id",letter).getSingleResult().getReadAt());assertEquals(1,em.createQuery("from ActivityDelivery where tenantId=:t and id=:id",ActivityDelivery.class).setParameter("t",tenant).setParameter("id",activity).getSingleResult().getOpenCount());return null;});
    });}
    @Test void tenantAndActorBoundaries(){in(otherTenant,()->{
        auth(c);assertEquals(2L,service.list("en",0,30).get("totalElements"));assertThrows(ResponseStatusException.class,()->service.detail("LETTER:"+letter,"en"));assertThrows(ResponseStatusException.class,()->service.detail("ANNOUNCEMENT:"+announcement,"en"));
        assertThrows(ResponseStatusException.class,()->service.read("ACTIVITY:"+activity,"en"));
    });in(tenant,()->{
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(String.valueOf(a),null,Collections.singleton(new SimpleGrantedAuthority("ROLE_ADMIN"))));assertThrows(AccessDeniedException.class,()->service.list("en",0,30));
    });assertThrows(AccessDeniedException.class,()->service.list("en",0,30));}
    @Test void readAllIsIdempotentScopedAndDoesNotClaim(){in(tenant,()->{
        auth(a);service.readAll("en");service.readAll("en");assertEquals(0L,service.list("en",0,30).get("unread"));assertNull(service.detail("ACTIVITY:"+activity,"en").get("claimedAt"));
        auth(b);assertEquals(2L,service.list("en",0,30).get("unread"));
    });in(otherTenant,()->{auth(c);assertEquals(2L,service.list("en",0,30).get("unread"));});}
    @Test void disabledPersonalMailDoesNotExposeRecipientsButKeepsAnnouncements(){in(tenant,()->{
        auth(a);config.inboxEnabled=false;assertEquals(2L,service.list("en",0,30).get("totalElements"));assertThrows(ResponseStatusException.class,()->service.detail("LETTER:"+letter,"en"));service.readAll("en");config.inboxEnabled=true;assertEquals(1L,service.list("en",0,30).get("unread"));
    });}
    @Test void concurrentReceiptRequestsDoNotDuplicate() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {List<Future<?>> pending=new ArrayList<>();for(int i=0;i<2;i++)pending.add(pool.submit(()->{in(tenant,()->{auth(a);service.read("ANNOUNCEMENT:"+announcement,"en");});SecurityContextHolder.clearContext();}));for(Future<?> f:pending)f.get(20,TimeUnit.SECONDS);}
        finally{pool.shutdownNow();}
        in(tenant,()->{auth(a);assertEquals(2L,service.list("en",0,30).get("unread"));});
    }
}
