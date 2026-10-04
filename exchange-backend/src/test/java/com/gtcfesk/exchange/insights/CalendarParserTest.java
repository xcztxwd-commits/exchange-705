package com.gtcfesk.exchange.insights;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CalendarParserTest {
    static final Instant NOW=Instant.parse("2026-10-03T05:40:00Z");
    static final ObjectMapper JSON=new ObjectMapper().registerModule(new JavaTimeModule());
    static String raw(String source)throws IOException{try(InputStream in=CalendarParserTest.class.getResourceAsStream("/calendar-official-20261003/"+source+".raw")){assertNotNull(in);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    static CalendarData find(List<CalendarData> rows,String metric,String period){return rows.stream().filter(d->d.metric.equals(metric)&&d.statisticalPeriod.equals(period)).findFirst().orElseThrow(()->new AssertionError(metric+" "+period));}
    static String ics(String time,String status,int sequence){return "BEGIN:VCALENDAR\r\nX-WR-TIMEZONE:America/New_York\r\nBEGIN:VEVENT\r\nUID:bls-employment-2026-09\r\nSUMMARY:Employment Situation for September 2026\r\nDTSTART"+time+"\r\nSEQUENCE:"+sequence+"\r\n"+status+"END:VEVENT\r\nEND:VCALENDAR\r\n";}
    @Test void officialBeaScheduleUsesActualUidsPeriodsAndUtc()throws Exception{
        List<CalendarData> rows=CalendarParser.ics("BEA",raw("bea-calendar"),NOW).events;assertEquals(46,rows.size());
        CalendarData pce=find(rows,"CORE_PCE_MOM","2026-09");assertEquals(Instant.parse("2026-10-29T12:30:00Z"),pce.releaseAt);assertEquals("ff8bd8ad-bb92-43c5-8a30-7e7b5baa33c2",pce.sourceUid);assertNull(pce.actual);assertNull(pce.forecast);assertEquals("14470937-5c28-3a64-bb16-e40208b32661",pce.eventId);
        CalendarData gdp=find(rows,"GDP_QOQ_ANNUALIZED","2026-Q3");assertEquals("ADVANCE",gdp.releaseStage);assertEquals("QOQ_ANNUALIZED",gdp.comparison);assertEquals("SA",gdp.seasonality);
        assertEquals(20,pce.releaseAt.atZone(ZoneId.of("Asia/Singapore")).getHour());
    }
    @Test void officialGdpActualIsAnnualizedPriorQuarterNotPreviousVintage()throws Exception{
        CalendarParser.Batch b=CalendarParser.beaRss(raw("bea-data"),NOW);assertEquals(1,b.events.size());CalendarData d=b.events.get(0);assertEquals("2026-Q2",d.statisticalPeriod);assertEquals("THIRD",d.releaseStage);assertEquals("2.2",d.actual);assertEquals("2.5",d.previous);assertTrue(d.isRevised);assertEquals("PERCENT",d.unit);assertEquals("OFFICIAL_RELEASE",d.actualBasis);assertTrue(b.events.stream().noneMatch(e->e.metric.equals("CORE_PCE_MOM")));
        assertThrows(Exception.class,()->CalendarParser.beaRss(raw("bea-data").replace("<changeUnit>PCT</changeUnit>","<changeUnit>USD</changeUnit>"),NOW));
    }
    @Test void officialBlsSeriesDeriveSpecificValuesWithoutInventingInitialVintages()throws Exception{
        List<CalendarData> rows=CalendarParser.bls(raw("bls-data"),NOW,JSON).observations;CalendarData nfp=find(rows,"NFP_CHANGE","2026-09"),unemployment=find(rows,"UNEMPLOYMENT_RATE","2026-09"),cpi=find(rows,"CPI_YOY","2026-08");
        assertEquals("29",nfp.actual);assertEquals("4.2",unemployment.actual);assertEquals("4.1",unemployment.previous);assertEquals("PERCENT",cpi.unit);assertEquals("YOY",cpi.comparison);assertEquals("NSA",cpi.seasonality);assertNotEquals("334.980",cpi.actual);assertEquals("3.4",cpi.actual);assertEquals("3.4",cpi.previous);
        for(CalendarData d:rows){assertFalse(d.historicalInitialKnown);assertNull(d.originalReleaseValue);assertEquals("UNKNOWN",d.timePrecision);assertNull(d.releaseAt);assertEquals("LATEST_TIME_SERIES",d.actualBasis);assertNull(d.forecast);}
        assertThrows(Exception.class,()->CalendarParser.bls(raw("bls-data").replace("REQUEST_SUCCEEDED","REQUEST_FAILED"),NOW,JSON));
    }
    @Test void fedDatePrecisionAndRealAnnouncementAssociationNeverAssumeReleaseTime()throws Exception{
        List<CalendarData> schedule=CalendarParser.fedCalendar(raw("fed-calendar"),NOW).events;CalendarData october=find(schedule,"FOMC_DECISION","2026-10");assertEquals(LocalDate.of(2026,10,28),october.releaseDate);assertEquals("DATE",october.timePrecision);assertNull(october.releaseAt);assertNull(october.actual);
        assertTrue(schedule.stream().noneMatch(d->d.metric.equals("FOMC_MINUTES")&&d.statisticalPeriod.equals("2026-10")));
        List<CalendarData> feed=CalendarParser.fedRss(raw("fed-data"),NOW,schedule).events;CalendarData decision=find(feed,"FOMC_DECISION","2026-09"),minutes=find(feed,"FOMC_MINUTES","2026-07");assertEquals(Instant.parse("2026-09-16T18:00:00Z"),decision.releaseAt);assertEquals(Instant.parse("2026-08-19T18:00:00Z"),minutes.releaseAt);assertEquals("MINUTE",decision.timePrecision);assertNull(decision.actual);
        assertEquals(LocalDate.of(2026,9,17),decision.releaseAt.atZone(ZoneId.of("Asia/Singapore")).toLocalDate());
    }
    @Test void stableIdentitySurvivesPostponementCancellationAndFoldedTitle(){
        CalendarData first=CalendarParser.ics("BLS",ics(";TZID=America/New_York:20261002T083000","",0),NOW).events.get(0);
        CalendarData next=CalendarParser.ics("BLS",ics(";TZID=America/New_York:20261106T083000","STATUS:CANCELLED\r\n",1),NOW).events.get(0);assertEquals(first.eventId,next.eventId);assertEquals("CANCELLED",next.status);assertEquals(Instant.parse("2026-10-02T12:30:00Z"),first.releaseAt);assertEquals(Instant.parse("2026-11-06T13:30:00Z"),next.releaseAt);
        assertEquals(first.eventId,CalendarParser.ics("BLS",ics(":20261002T123000Z","",0).replace("September 2026","September\r\n  2026"),NOW).events.get(0).eventId);
    }
    @Test void dstGapsAndAmbiguityRequireExplicitUtc(){
        assertThrows(Exception.class,()->CalendarParser.ics("BLS",ics(";TZID=America/New_York:20260308T023000","",0),NOW));
        assertThrows(Exception.class,()->CalendarParser.ics("BLS",ics(";TZID=America/New_York:20261101T013000","",0),NOW));
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"),CalendarParser.ics("BLS",ics(":20261101T053000Z","",0),NOW).events.get(0).releaseAt);
        assertThrows(Exception.class,()->CalendarParser.ics("BLS",ics(":20261002T123000Z","RRULE:FREQ=MONTHLY\r\n",0),NOW));
    }
    @Test void missingDataWrongUnitForecastAndUrlsAreRejected(){
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->CalendarData.metric(null));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new CalendarData().validate());
        CalendarData d=CalendarData.metric("CORE_PCE_MOM");d.statisticalPeriod="2026-09";d.sourceUrl=CalendarParser.BEA_ICS;d.validate();assertNull(d.actual);assertNull(d.forecast);
        d.releaseStage="MEETING";assertThrows(Exception.class,d::validate);d.releaseStage="INITIAL";d.historicalInitialKnown=true;assertThrows(Exception.class,d::validate);d.historicalInitialKnown=false;
        d.unit="INDEX";assertThrows(Exception.class,d::validate);d.unit="PERCENT";d.forecast="0";assertThrows(Exception.class,d::validate);d.forecast=null;d.actual="NaN";assertThrows(Exception.class,d::validate);d.actual=null;
        d.adminEstimate="-0.1";assertThrows(Exception.class,d::validate);d.estimateReason="独立管理员估计，测试输入，不是官方数据";d.validate();assertEquals("ADMIN_ESTIMATE",d.estimateKind);
        d.sourceUrl="https://www.bea.gov@127.0.0.1/";assertThrows(Exception.class,d::validate);d.sourceUrl="https://www.bls.gov/";assertThrows(Exception.class,d::validate);
    }
    @Test void xmlExternalEntitiesCannotBeReadAndOversizeFails(){
        assertThrows(Exception.class,()->CalendarParser.beaRss("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///not-read'>]><rss>&x;</rss>",NOW));
        assertThrows(Exception.class,()->CalendarParser.fedRss("<!DOCTYPE x SYSTEM 'https://example.invalid/'><rss/>",NOW,Collections.emptyList()));
        char[] oversized=new char[2000001];Arrays.fill(oversized,'a');assertThrows(Exception.class,()->CalendarParser.beaRss(new String(oversized),NOW));
    }
    @Test void calendarExportFoldsUtf8AndRetryAfterRespectsSource(){
        String folded=CalendarService.foldIcs("SUMMARY:这是很长的中文标题重复检查不可截断字符这是很长的中文标题重复检查不可截断字符\r\nEND:VEVENT\r\n");for(String line:folded.split("\r\n"))assertTrue(line.getBytes(StandardCharsets.UTF_8).length<=75);assertTrue(folded.contains("\r\n "));
        assertEquals(NOW.plusSeconds(600),CalendarSync.retryAfter("600",NOW));assertNull(CalendarSync.retryAfter("-1",NOW));assertEquals(Instant.parse("2026-10-03T06:00:00Z"),CalendarSync.retryAfter("Sat, 03 Oct 2026 06:00:00 GMT",NOW));
    }
}
