package com.gtcfesk.exchange.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.Connection;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only the loopback, disposable fixture created by Test-MySql.ps1; never a production connection. */
@EnabledIfEnvironmentVariable(named="EQUITY_TEST_JDBC",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/equity_test.*")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssetEquityMySqlTest {
    static DriverManagerDataSource source;
    static JdbcTemplate db;
    static AssetEquityStore store;
    static EquityValuationService valuation;
    static AssetEquityJobs jobs;
    @BeforeAll static void setup() throws Exception {
        source=new DriverManagerDataSource(System.getenv("EQUITY_TEST_JDBC"),"root","equity-fixture-only");db=new JdbcTemplate(source);
        assertTrue(db.queryForObject("select version()",String.class).startsWith("5.7."));assertEquals("equity_test",db.queryForObject("select database()",String.class));
        try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/asset-equity/V001__net_equity_history.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("db/asset-equity/V001__net_equity_history.sql"));}
        db.execute("alter table asset_history_1m add column effective_at bigint null");
        store=new AssetEquityStore(source,new ObjectMapper());valuation=new EquityValuationService(db,mock(ForexQuoteMarketService.class));jobs=new AssetEquityJobs(store,valuation);
        db.execute("create table user_account(id bigint primary key)");
        db.execute("create table asset_account(user_id bigint,coin varchar(20),available decimal(32,16),frozen decimal(32,16))");
        db.execute("create table contract_order(id bigint,user_id bigint,status varchar(20),symbol varchar(32),side varchar(10),quantity decimal(32,16),open_price decimal(32,16),lot_size decimal(32,16),leverage decimal(32,16),fee decimal(32,16),quote_currency varchar(16),quote_source varchar(16),profit decimal(32,16),close_time timestamp null)");
        db.execute("create table option_order(user_id bigint,status varchar(20),amount decimal(32,16),profit decimal(32,16),close_time timestamp null)");
        db.execute("create table loan_record(id bigint,user_id bigint,amount decimal(32,16),daily_rate decimal(8,6),free_days int,status varchar(20),approved_at timestamp null,actual_repayment_at timestamp null,repayment_date timestamp null,overdue_fee decimal(32,16),total_interest decimal(32,16),repayment_amount decimal(32,16))");
        db.execute("create table financial_yield_record(user_id bigint,status varchar(20),daily_yield decimal(32,16),paid_at timestamp null)");
        db.execute("create table system_config(config_key varchar(100),config_value varchar(100))");
        db.execute("create table asset_snapshot(id bigint primary key,user_id bigint,total decimal(32,16),captured_at bigint)");
    }
    @AfterAll static void stop(){if(jobs!=null)jobs.stop();}
    @Test void ddlRepeatableAndIndexesExist(){
        assertEquals(1,db.queryForObject("select count(*) from asset_history_migration",Integer.class));
        for(String table:AssetEquityStore.TABLES){assertEquals(3,db.queryForObject("select count(*) from information_schema.statistics where table_schema='equity_test' and table_name=? and index_name='PRIMARY'",Integer.class,table));assertEquals(3,db.queryForObject("select count(*) from information_schema.statistics where table_schema='equity_test' and table_name=? and index_name<>'PRIMARY'",Integer.class,table));}
    }
    @Test void allExistingUsersIncludingZeroAndCostOptionsAreSampledOnceConcurrently() throws Exception {
        db.update("insert into user_account values(100),(101),(102)");db.update("insert into asset_account values(100,'FUND',900,100),(101,'OPTION',100,50)");
        db.update("insert into option_order(user_id,status,amount) values(101,'TRADING',50)");db.update("insert into financial_yield_record(user_id,status,daily_yield) values(100,'PENDING',10),(100,'PAID',100)");
        ExecutorService threads=Executors.newFixedThreadPool(2);
        try{Future<?> a=threads.submit(jobs::capture),b=threads.submit(jobs::capture);a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);}finally{threads.shutdownNow();}
        for(long user:new long[]{100,101,102})assertEquals(1,db.queryForObject("select count(*) from asset_history_1m where user_id=?",Integer.class,user));
        AssetEquityValuationTest.equal("1010",db.queryForObject("select net_equity from asset_history_1m where user_id=100",BigDecimal.class));
        AssetEquityValuationTest.equal("150",db.queryForObject("select net_equity from asset_history_1m where user_id=101",BigDecimal.class));
        AssetEquityValuationTest.equal("0",db.queryForObject("select net_equity from asset_history_1m where user_id=102",BigDecimal.class));
        String evidence=db.queryForObject("select valuation_evidence from asset_history_1m where user_id=101",String.class);assertTrue(evidence.contains("option_principal_cost_not_fair_value"));
        jobs.aggregate();
        for(int level=1;level<=3;level++)
            assertEquals(0,db.queryForObject("select count(*) from "+AssetEquityStore.TABLES[level]+" where user_id=101 and finalized=0",Integer.class));
    }
    @Test @Order(1) void hierarchicalRecoveryNullsIdempotenceAndBoundaryClipping(){
        // A two-day-old UTC bucket is complete even before the 00:03:20 daily run.
        long start=AssetHistoryBucket.floor(System.currentTimeMillis(),86400000)-2*86400000L;
        store.locked("fixture",s->{
            EquityValuationService.Batch batch=new EquityValuationService.Batch();List<EquityValuationService.Value> values=new ArrayList<>();
            for(int i=0;i<1440;i++){EquityValuationService.Value v=new EquityValuationService.Value(200,start+i*60000+5000);v.add("wallet_balance",BigDecimal.valueOf(i==10?9000:i==11?-900:i));if(i==12)v.missing("contract_unrealized_pnl","QUOTE_STALE");v.finish();values.add(v);}
            store.saveMinutes(s.db,batch,values);
        });
        jobs.aggregate();jobs.aggregate();
        Map<String,Object> day=db.queryForMap("select * from asset_history_1d where user_id=200 and bucket_start=?",start);
        AssetEquityValuationTest.equal("9000",(BigDecimal)day.get("high_value"));AssetEquityValuationTest.equal("-900",(BigDecimal)day.get("low_value"));assertEquals(start+605000,((Number)day.get("high_at")).longValue());
        assertEquals(1439,((Number)day.get("valid_sample_count")).intValue());assertEquals(1,((Number)day.get("invalid_sample_count")).intValue());assertEquals("PARTIAL",day.get("quality"));
        assertEquals(1440,db.queryForObject("select count(*) from asset_history_1m where user_id=200",Integer.class));
        List<AssetHistoryBucket> clipped=store.window(db,3,200,start+37*60000,start+86400000,System.currentTimeMillis());
        AssetHistoryBucket all=new AssetHistoryBucket(200,start+37*60000,start+86400000);clipped.forEach(all::merge);AssetEquityValuationTest.equal("1439",all.high);AssetEquityValuationTest.equal("37",all.low);
        AssetHistoryBucket stale=new AssetHistoryBucket(200,start,start+86400000);stale.merge(AssetHistoryRollupTest.sample(start,"99999"));store.saveBucket(db,3,stale,System.currentTimeMillis());
        AssetEquityValuationTest.equal("9000",db.queryForObject("select high_value from asset_history_1d where user_id=200 and bucket_start=?",BigDecimal.class,start));
        assertEquals(0,db.queryForObject("select count(*) from asset_history_1m where user_id=200 and bucket_start>=?",Integer.class,start+86400000));
    }
    @Test void closingSnapshotKeepsActualTimeRejectsLateAndHidesCurrentParents(){
        long boundary=AssetHistoryBucket.floor(System.currentTimeMillis(),60000);
        EquityValuationService.Value v=new EquityValuationService.Value(600,boundary+1234);
        v.add("wallet_balance",new BigDecimal("75"));v.finish();
        store.saveMinutes(db,new EquityValuationService.Batch(),Collections.singletonList(v),boundary);
        Map<String,Object> row=db.queryForMap("select bucket_start,observed_at from asset_history_1m where user_id=600");
        assertEquals(boundary-60000,((Number)row.get("bucket_start")).longValue());
        assertEquals(boundary+1234,((Number)row.get("observed_at")).longValue());
        v=new EquityValuationService.Value(600,boundary+60000);v.finish();
        final EquityValuationService.Value late=v;
        assertThrows(IllegalArgumentException.class,()->store.saveMinutes(db,new EquityValuationService.Batch(),Collections.singletonList(late),boundary));
        for(int level=1;level<=3;level++) {
            long size=AssetEquityStore.INTERVALS[level],start=AssetHistoryBucket.floor(boundary,size);
            AssetHistoryBucket draft=new AssetHistoryBucket(600,start,start+size);
            draft.merge(AssetHistoryRollupTest.sample(boundary,"75"));
            store.saveBucket(db,level,draft,boundary);
            assertTrue(store.window(db,level,600,start,boundary+2000,boundary+2000).isEmpty());
        }
    }
    @Test void firstObservationBaselineAndVersionIsolation(){
        long t=AssetHistoryBucket.floor(System.currentTimeMillis(),60000)-600000;
        for(String amount:Arrays.asList("42","999"))store.locked("fixture",s->{EquityValuationService.Value v=new EquityValuationService.Value(300,t+5000);v.add("wallet_balance",new BigDecimal(amount));v.finish();store.saveMinutes(s.db,new EquityValuationService.Batch(),Collections.singletonList(v));});
        AssetEquityValuationTest.equal("42",db.queryForObject("select first_positive from asset_history_baseline where user_id=300",BigDecimal.class));
        db.update("insert into asset_history_baseline values(300,'wallet_balance_v1',1,999,1)");
        AssetEquityHistoryService api=new AssetEquityHistoryService(store,valuation);Map<String,Object> opening=api.opening(db,300,t-100000,System.currentTimeMillis());assertEquals("EARLIEST_POSITIVE_NET_EQUITY",opening.get("source"));AssetEquityValuationTest.equal("42",new BigDecimal(opening.get("value").toString()));assertNull(api.opening(db,999999,t,System.currentTimeMillis()));
    }
    @Test void rollbackCoversDataAndCursorAndLockIsReleased(){
        assertThrows(IllegalStateException.class,()->store.locked("failure",s->{store.progress(s.db,"failure",123,9,1);throw new IllegalStateException("fixture");}));
        assertEquals(0,db.queryForObject("select count(*) from asset_history_job_state where task_name='failure'",Integer.class));assertEquals(1,db.queryForObject("select is_free_lock('equity_v1_failure')",Integer.class));
        store.locked("failure",s->store.progress(s.db,"failure",124,10,1));assertEquals(124,store.state(db,"failure",0)[0]);
    }
    @Test void liveApiUsesCorrespondingTablesNoLegacyAndNoFuture(){
        db.update("insert into asset_account values(400,'FUND',-5,0),(401,'FUND',999,0)");db.update("insert into asset_snapshot values(1,400,99999,1)");
        AssetEquityHistoryService api=new AssetEquityHistoryService(store,valuation);
        String[] ranges={"1D","1W","1M","1Y"};for(int i=0;i<4;i++){
            Map<String,Object> r=api.history(400L,ranges[i]);assertEquals(AssetEquityStore.TABLES[i],r.get("sourceTable"));assertEquals(AssetEquityStore.INTERVALS[i],r.get("intervalMs"));assertEquals(2,r.get("schemaVersion"));AssetEquityValuationTest.equal("-5",new BigDecimal(r.get("total").toString()));assertNull(r.get("incomePercent"));assertTrue(((List<?>)r.get("points")).isEmpty());
        }
    }
    @Test void repeatableReadDoesNotMixWalletBeforeAndAfterSettlement(){
        db.update("insert into asset_account values(500,'FUND',1,0)");
        store.read(snapshot->{assertEquals(1,snapshot.queryForObject("select available from asset_account where user_id=500",Integer.class));db.update("update asset_account set available=2 where user_id=500");assertEquals(1,snapshot.queryForObject("select available from asset_account where user_id=500",Integer.class));return null;});
        assertEquals(2,db.queryForObject("select available from asset_account where user_id=500",Integer.class));
    }
}
