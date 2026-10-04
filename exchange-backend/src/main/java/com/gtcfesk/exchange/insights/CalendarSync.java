package com.gtcfesk.exchange.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.annotation.PostConstruct;
import javax.persistence.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** One durable request lease and budget per environment/source, not per tenant or visitor. */
@Service
public class CalendarSync {
    public static final Map<String,String> URLS;
    static {Map<String,String> urls=new LinkedHashMap<>();urls.put("BLS_CALENDAR",CalendarParser.BLS_ICS);urls.put("BEA_CALENDAR",CalendarParser.BEA_ICS);urls.put("FED_CALENDAR",CalendarParser.FED_CAL);urls.put("BEA_DATA",CalendarParser.BEA_RSS);urls.put("FED_DATA",CalendarParser.FED_RSS);urls.put("BLS_DATA",CalendarParser.BLS_API);URLS=Collections.unmodifiableMap(urls);}
    @PersistenceContext private EntityManager em;
    @Value("${calendar.sync.enabled:false}") private boolean enabled;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final CalendarService service;
    public CalendarSync(PlatformTransactionManager manager,ObjectMapper json,CalendarService service){tx=new TransactionTemplate(manager);this.json=json;this.service=service;}
    private String id(String source){return service.environment()+":"+source;}
    private static long interval(String source){return source.equals("BLS_DATA")?21600:3600;}
    private static int budget(String source){return source.equals("BLS_DATA")?20:24;}
    @PostConstruct public void initialize(){tx.execute(s->{for(String name:URLS.keySet())if(em.find(CalendarSource.class,id(name))==null){CalendarSource r=new CalendarSource();r.setId(id(name));r.setEnvironment(service.environment());r.setSourceId(name);em.persist(r);}return null;});}
    public boolean enabled(){return enabled;}
    public List<Map<String,Object>> status(){return tx.execute(s->{List<Map<String,Object>> result=new ArrayList<>();for(String name:URLS.keySet()){CalendarSource r=em.find(CalendarSource.class,id(name));Map<String,Object> m=new LinkedHashMap<>();m.put("sourceId",name);m.put("url",URLS.get(name));m.put("status",r.getStatus());m.put("httpStatus",r.getHttpStatus());m.put("lastAttempt",r.getLastAttempt());m.put("lastSuccess",r.getLastSuccess());m.put("nextAttempt",r.getNextAttempt());m.put("requestsToday",r.getRequestsToday());m.put("budgetDate",r.getBudgetDate());m.put("dailyBudget",budget(name));m.put("minimumIntervalSeconds",interval(name));m.put("lastError",r.getLastError());m.put("stale",r.getLastSuccess()==null||r.getLastSuccess().plusSeconds(interval(name)*2).isBefore(Instant.now()));m.put("autoSyncEnabled",enabled);m.put("corePceActual",name.equals("BEA_DATA")?"RSS_NOT_PROVIDED; VERIFIED_OFFICIAL_MATERIAL_SUPPORTED":null);result.add(m);}return result;});}
    private boolean claim(String source,Instant now){return tx.execute(s->{CalendarSource r=em.find(CalendarSource.class,id(source),LockModeType.PESSIMISTIC_WRITE);if(r.getLeaseUntil()!=null&&r.getLeaseUntil().isAfter(now)||r.getNextAttempt()!=null&&r.getNextAttempt().isAfter(now))return false;
        LocalDate day=now.atZone(ZoneOffset.UTC).toLocalDate();if(!day.equals(r.getBudgetDate())){r.setBudgetDate(day);r.setRequestsToday(0);}if(r.getRequestsToday()>=budget(source)){r.setStatus("BUDGET_EXHAUSTED");r.setNextAttempt(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());return false;}
        r.setLeaseUntil(now.plusSeconds(60));r.setLastAttempt(now);r.setRequestsToday(r.getRequestsToday()+1);return true;});}
    public static class HttpResult {public int status;public String body,etag,modified,retryAfter;}
    HttpResult fetch(String source,CalendarSource state)throws IOException{
        HttpURLConnection c=(HttpURLConnection)new URL(URLS.get(source)).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(4000);c.setReadTimeout(8000);c.setRequestProperty("User-Agent","ExchangeCalendar/1.0");c.setRequestProperty("Accept",source.equals("BLS_DATA")?"application/json":source.equals("FED_CALENDAR")?"text/html":source.endsWith("_CALENDAR")?"text/calendar":"application/rss+xml,application/xml,text/xml");if(state.getEtag()!=null)c.setRequestProperty("If-None-Match",state.getEtag());if(state.getLastModified()!=null)c.setRequestProperty("If-Modified-Since",state.getLastModified());
        HttpResult result=new HttpResult();long deadline=System.nanoTime()+12000000000L;try{if(source.equals("BLS_DATA")){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] request="{\"seriesid\":[\"CES0000000001\",\"LNS14000000\",\"CUUR0000SA0\"]}".getBytes(StandardCharsets.UTF_8);try(OutputStream out=c.getOutputStream()){out.write(request);}}result.status=c.getResponseCode();result.retryAfter=c.getHeaderField("Retry-After");result.etag=c.getHeaderField("ETag");result.modified=c.getHeaderField("Last-Modified");if(result.status==200){try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count,total=0;while((count=in.read(buffer))!=-1){total+=count;if(total>2000000)throw new IOException("Official response exceeds 2 MB");if(System.nanoTime()>deadline)throw new IOException("Official response budget exceeded");out.write(buffer,0,count);}result.body=new String(out.toByteArray(),StandardCharsets.UTF_8).replaceFirst("^\\uFEFF","");}}return result;}finally{c.disconnect();}
    }
    public Map<String,Object> sync(String source){if(!URLS.containsKey(source))throw CalendarData.bad("未知官方来源");Instant now=Instant.now();boolean requested=claim(source,now);if(requested){HttpResult result=null;String error=null;CalendarParser.Batch batch=null;
        try{CalendarSource state=tx.execute(s->em.find(CalendarSource.class,id(source)));result=fetch(source,state);if(result.status==200){batch=parse(source,result.body,now);}else if(result.status!=304)error="Official HTTP "+result.status;}catch(Exception e){error=e.getClass().getSimpleName()+": "+Optional.ofNullable(e.getMessage()).orElse("parse/transport failure");}
        final HttpResult response=result;final CalendarParser.Batch parsed=batch;final String failure=error;
        tx.execute(s->{CalendarSource r=em.find(CalendarSource.class,id(source),LockModeType.PESSIMISTIC_WRITE);r.setLeaseUntil(null);r.setHttpStatus(response==null?null:response.status);CalendarSourceUpdate update=new CalendarSourceUpdate();update.setEnvironment(service.environment());update.setSourceId(source);update.setCapturedAt(now);update.setHttpStatus(r.getHttpStatus());
            if(failure==null&&response!=null&&(response.status==200||response.status==304&&r.getParsedJson()!=null)){r.setStatus("OK");r.setFailures(0);r.setLastError(null);r.setLastSuccess(now);r.setNextAttempt(now.plusSeconds(interval(source)));if(response.status==200){r.setPayload(response.body);r.setParsedJson(encode(parsed));r.setEtag(shorten(response.etag,300));r.setLastModified(shorten(response.modified,300));update.setResponseHash(digest(response.body));}}
            else {r.setFailures(Math.min(20,r.getFailures()+1));r.setStatus(response!=null&&response.status==403?"BLOCKED":response!=null&&response.status==429?"RATE_LIMITED":"ERROR");r.setLastError(shorten(failure==null?"No cached response for HTTP 304":failure,500));long delay=Math.max(interval(source),Math.min(86400,3600L*(1L<<Math.min(5,r.getFailures()-1))));if(response!=null&&response.status==403)delay=Math.max(delay,43200);Instant retry=retryAfter(response==null?null:response.retryAfter,now);r.setNextAttempt(retry!=null&&retry.isAfter(now.plusSeconds(delay))?retry:now.plusSeconds(delay));}
            update.setStatus(r.getStatus());update.setError(r.getLastError());em.persist(update);return null;});
        }
        int changed=TenantContext.currentTenantId()==null?0:importCached(source);Map<String,Object> out=new LinkedHashMap<>();out.put("requested",requested);out.put("changed",changed);out.put("sources",status());return out;
    }
    CalendarParser.Batch parse(String source,String body,Instant now)throws Exception{
        switch(source){case "BLS_CALENDAR":return CalendarParser.ics("BLS",body,now);case "BEA_CALENDAR":return CalendarParser.ics("BEA",body,now);case "FED_CALENDAR":return CalendarParser.fedCalendar(body,now);case "BEA_DATA":return CalendarParser.beaRss(body,now);case "BLS_DATA":return CalendarParser.bls(body,now,json);case "FED_DATA":return CalendarParser.fedRss(body,now,cached("FED_CALENDAR").events);default:throw CalendarData.bad("未知来源");}
    }
    private CalendarParser.Batch cached(String source){return tx.execute(s->{CalendarSource r=em.find(CalendarSource.class,id(source));try{return r.getParsedJson()==null?new CalendarParser.Batch():json.readValue(r.getParsedJson(),CalendarParser.Batch.class);}catch(Exception e){throw new IllegalStateException("持久化来源缓存无法读取",e);}});}
    public int importCached(String source){return service.importBatch(cached(source),false,null);}
    public int importAllCached(){int count=0;for(String source:URLS.keySet())count+=importCached(source);return count;}
    public List<CalendarSourceUpdate> history(String source,int page){if(!URLS.containsKey(source)||page<0||page>10000)throw CalendarData.bad("来源/分页无效");return tx.execute(s->em.createQuery("from CalendarSourceUpdate where environment=:env and sourceId=:source order by id desc",CalendarSourceUpdate.class).setParameter("env",service.environment()).setParameter("source",source).setFirstResult(page*50).setMaxResults(50).getResultList());}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String shorten(String value,int limit){return value==null?null:value.substring(0,Math.min(value.length(),limit)).replaceAll("[\\r\\n]"," ");}
    private static String digest(String body){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    static Instant retryAfter(String value,Instant now){if(value==null)return null;try{long seconds=Long.parseLong(value);return seconds<0?null:now.plusSeconds(Math.min(seconds,604800));}catch(Exception e){try{return ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(Exception ignored){return null;}}}
}
