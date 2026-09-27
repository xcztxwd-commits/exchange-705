package com.gtcfesk.exchange.user;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.math.BigDecimal;
import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import static org.junit.jupiter.api.Assertions.*;

class AssetHistoryTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AssetHistoryService service;
    @BeforeEach void setup() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table asset_account(user_id bigint, coin varchar(32), available decimal(32,16), frozen decimal(32,16))");
        jdbc.execute("create table asset_snapshot(id bigint auto_increment primary key,user_id bigint,captured_at bigint,total decimal(32,16))");
        jdbc.execute("create table system_config(config_key varchar(100),config_value varchar(100))");
        jdbc.execute("create table option_order(user_id bigint,status varchar(20),profit decimal(32,16),close_time timestamp)");
        jdbc.execute("create table contract_order(user_id bigint,status varchar(20),profit decimal(32,16),fee decimal(32,16),lot_size decimal(32,16),close_time timestamp)");
        jdbc.execute("create table financial_yield_record(user_id bigint,status varchar(20),daily_yield decimal(32,16),paid_at timestamp)");
        jdbc.update("insert into asset_account values(1,'FUND',100,20),(1,'CONTRACT',50,0),(2,'FUND',999,0)");
        service = new AssetHistoryService(jdbc);
    }
    @AfterEach void close() { database.shutdown(); }
    @Test void incomePercentageUsesCalendarOpeningAndMissingOpeningIsZero() {
        long start = AssetHistoryService.periodStart("1M", Instant.now(), ZoneId.of("Europe/London"));
        assertEquals(BigDecimal.ZERO, service.incomeOpening(1L, start, System.currentTimeMillis()));
        jdbc.update("insert into asset_snapshot(user_id,captured_at,total) values(1,?,200),(2,?,999),(1,?,500)", start - 30000, start, start + 60000);
        assertEquals(0, new BigDecimal("200").compareTo(service.incomeOpening(1L, start, System.currentTimeMillis())));
        Map<String,Object> result = service.history(1L, "1M");
        assertEquals(0, new BigDecimal("200").compareTo(new BigDecimal((String)result.get("incomeOpening"))));
        assertEquals("0.00", result.get("incomePercent"));
        assertEquals("5.00", AssetHistoryService.incomePercent(new BigDecimal("10"), new BigDecimal("200")));
        assertEquals("-12.47", AssetHistoryService.incomePercent(new BigDecimal("-24.93238"), new BigDecimal("200")));
        assertEquals("0.00", AssetHistoryService.incomePercent(BigDecimal.ZERO, BigDecimal.ZERO));
        assertNull(AssetHistoryService.incomePercent(BigDecimal.ONE, BigDecimal.ZERO));
        assertNull(AssetHistoryService.incomePercent(BigDecimal.ONE.negate(), BigDecimal.ZERO));
        assertEquals(0, new BigDecimal("200").compareTo(service.incomeOpening(1L, start + 300000, System.currentTimeMillis())));
    }
    @Test void missingOrZeroOpeningFallsBackToEarliestPositiveObservedAssets() {
        long start = 1000000, now = 2000000;
        jdbc.update("insert into asset_snapshot(user_id,captured_at,total) values(2,1,999),(1,?,888)", now + 1);
        assertEquals(BigDecimal.ZERO, service.incomeOpening(1L, start, now));
        jdbc.update("insert into asset_snapshot(user_id,captured_at,total) values(1,100,0),(1,200,250),(1,300,400),(1,?,0)", start);
        assertEquals(0, new BigDecimal("250").compareTo(service.incomeOpening(1L, start, now)));
        assertEquals(0, new BigDecimal("250").compareTo(service.incomeOpening(1L, start + 300000, now)));
        jdbc.update("insert into asset_snapshot(user_id,captured_at,total) values(1,?,500)", start);
        assertEquals(0, new BigDecimal("500").compareTo(service.incomeOpening(1L, start, now)));
        assertEquals("10.00", AssetHistoryService.incomePercent(new BigDecimal("25"), service.incomeOpening(1L, start + 300000, now)));
    }
    @Test void realBalancesAndInternalTransfersRemainConsistent() {
        assertEquals(0, new BigDecimal((String) service.history(1L,"1D").get("total")).compareTo(new BigDecimal("170")));
        assertEquals(false, service.history(1L,"1D").get("hasHistory"));
        service.capture();
        jdbc.update("update asset_account set available=available-10, frozen=frozen+10 where user_id=1 and coin='FUND'");
        assertEquals(0, new BigDecimal((String) service.history(1L,"1D").get("total")).compareTo(new BigDecimal("170")));
        jdbc.update("update asset_account set available=available+25 where user_id=1 and coin='FUND'");
        Map<String,Object> history=service.history(1L,"1D");
        assertEquals(true,history.get("hasHistory"));
        assertEquals(0,new BigDecimal((String)history.get("total")).compareTo(new BigDecimal("195")));
        List<?> points=(List<?>)history.get("points");
        assertEquals(2,points.size());
        assertEquals(history.get("total"),((Map<?,?>)points.get(points.size()-1)).get("value"));
    }
    @Test void bucketsBoundResponseAndNoOtherUsersLeak() {
        long now=System.currentTimeMillis();
        for(int i=0;i<1000;i++)jdbc.update("insert into asset_snapshot(user_id,captured_at,total) values(1,?,?)",now-i*60000L,new BigDecimal("10"));
        service.capture();
        List<?> points=(List<?>)service.history(1L,"1D").get("points");
        assertTrue(points.size()<=1442);
        assertTrue(points.stream().noneMatch(p->new BigDecimal((String)((Map<?,?>)p).get("value")).compareTo(new BigDecimal("999"))==0));
    }
    @Test void authenticationRangeAndRetention() {
        AssetHistoryController controller=new AssetHistoryController(service);
        assertEquals(401,controller.get("1D",null).getStatusCodeValue());
        UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("1",null,Collections.emptyList());
        assertEquals(400,controller.get("bad",auth).getStatusCodeValue());
        assertEquals(200,controller.get("1M",auth).getStatusCodeValue());
        jdbc.update("insert into asset_snapshot(user_id,captured_at,total) values(1,0,10)");
        service.prune();
        assertEquals(1,jdbc.queryForObject("select count(*) from asset_snapshot",Integer.class));
    }
    @Test void samplingPreservesTrueExtremaAndKnownZeroOpening() {
        long now=System.currentTimeMillis();
        jdbc.update("insert into asset_snapshot(user_id,captured_at,total) values(1,?,0)",now-86400000-30000);
        jdbc.update("insert into asset_snapshot(user_id,captured_at,total) values(1,?,0)",now-20000);
        jdbc.update("insert into asset_snapshot(user_id,captured_at,total) values(1,?,1000)",now-10000);
        jdbc.update("insert into asset_snapshot(user_id,captured_at,total) values(1,?,10)",now-5000);
        Map<String,Object> history=service.history(1L,"1D");
        List<?> points=(List<?>)history.get("points");
        Map<?,?> opening=(Map<?,?>)points.get(0);
        assertEquals(history.get("from"),opening.get("time"));
        assertNotNull(opening.get("observedAt"));
        assertEquals(0,new BigDecimal((String)opening.get("value")).compareTo(BigDecimal.ZERO));
        assertTrue(points.stream().anyMatch(p->new BigDecimal((String)((Map<?,?>)p).get("value")).compareTo(new BigDecimal("1000"))==0));
        assertTrue(points.stream().noneMatch(p->(Long)((Map<?,?>)p).get("time")<(Long)history.get("from")));
    }
    @Test void rollingWindowsUseExplicitResolutionAndDoNotStretchRecentRecords() {
        service.capture();
        String[] ranges={"1D","1W","1M","1Y"};
        long[] durations={86400000L,7L*86400000,30L*86400000,365L*86400000};
        long[] intervals={60000L,3600000L,4L*3600000,86400000L};
        for(int i=0;i<ranges.length;i++) {
            Map<String,Object> result=service.history(1L,ranges[i]);
            long from=(Long)result.get("from"),to=(Long)result.get("asOf");
            assertEquals(durations[i],to-from);
            assertEquals(intervals[i],result.get("intervalMs"));
            List<?> points=(List<?>)result.get("points");
            assertTrue((Long)((Map<?,?>)points.get(0)).get("time")>from);
            assertEquals(to,((Map<?,?>)points.get(points.size()-1)).get("time"));
        }
    }
    @Test void calendarBoundariesAndNetIncomeExcludeDepositsAndOtherUsers() {
        Instant now=Instant.parse("2026-09-27T08:00:00Z");
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        assertEquals(Instant.parse("2026-09-26T16:00:00Z").toEpochMilli(),AssetHistoryService.periodStart("1D",now,zone));
        assertEquals(Instant.parse("2026-09-20T16:00:00Z").toEpochMilli(),AssetHistoryService.periodStart("1W",now,zone));
        assertEquals(Instant.parse("2026-08-31T16:00:00Z").toEpochMilli(),AssetHistoryService.periodStart("1M",now,zone));
        assertEquals(Instant.parse("2025-12-31T16:00:00Z").toEpochMilli(),AssetHistoryService.periodStart("1Y",now,zone));
        long end=System.currentTimeMillis(),start=end-3600000;
        Timestamp settled=new Timestamp(end-10000);
        jdbc.update("insert into option_order values(1,'CLOSED',10,?),(2,'CLOSED',900,?),(1,'TRADING',500,?)",settled,settled,settled);
        jdbc.update("insert into contract_order values(1,'CLOSED',20,2,1,?),(1,'CLOSED',5,2,null,?)",settled,settled);
        jdbc.update("insert into financial_yield_record values(1,'PAID',3,?),(1,'PENDING',999,?)",settled,settled);
        assertEquals(0,new BigDecimal("36").compareTo(service.income(1L,start,end)));
        jdbc.update("update asset_account set available=available+10000 where user_id=1");
        assertEquals(0,new BigDecimal("36").compareTo(service.income(1L,start,end)));
        assertEquals(0,BigDecimal.ZERO.compareTo(service.income(1L,end,end+10000)));
    }
}
