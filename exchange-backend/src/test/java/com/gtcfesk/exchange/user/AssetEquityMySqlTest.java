package com.gtcfesk.exchange.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.sql.Connection;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual equity services on an identified/restored current-schema clone; initial amounts are synthetic fixture inputs. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssetEquityMySqlTest {
    static DriverManagerDataSource source;
    static JdbcTemplate db;
    static AssetEquityStore store;
    static EquityValuationService valuation;
    static AssetEquityJobs jobs;
    @BeforeAll static void setup() throws Exception {
        source=com.gtcfesk.exchange.tenant.DedicatedMysqlFixture.fromProperty("equity.mysql.fixture");db=new JdbcTemplate(source);
        assertTrue(db.queryForObject("select database()",String.class).startsWith("mt705_probe_"));
        assertEquals(2026100402L,db.queryForObject("select max(minimum_application_epoch) from tenant_schema_version",Long.class));
        assertEquals("stage2_money_a",db.queryForObject("select code from tenant where id=2",String.class));
        assertEquals(0L,db.queryForObject("select count(*) from user_account where tenant_id IN (2,3)",Long.class),"Use a fresh exclusive equity-suite clone; do not erase another suite's rows");
        List<Map<String,Object>> metadata=db.queryForList("select * from tenant_schema_version");
        try(Connection c=source.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/asset-equity/V001__net_equity_history.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("db/asset-equity/V001__net_equity_history.sql"));}
        assertEquals(metadata,db.queryForList("select * from tenant_schema_version"),"Legacy repeatable DDL must not replace current schema receipts");
        assertEquals(1,db.queryForObject("select count(*) from information_schema.columns where table_schema=DATABASE() and table_name='asset_history_1m' and column_name='effective_at'",Integer.class));
        store=new AssetEquityStore(source,new ObjectMapper());valuation=new EquityValuationService(db,mock(ForexQuoteMarketService.class));jobs=new AssetEquityJobs(store,valuation);
    }
    TenantContext.Scope scope;
    @BeforeEach void enterTenant(){scope=TenantContext.open(2L);}
    @AfterEach void leaveTenant(){scope.close();}
    static void users(long... ids){for(long id:ids)db.update("insert into user_account(tenant_id,id,email,password_hash,row_version) values(2,?,?, 'not-a-login',0)",id,"equity-"+id+"@test.invalid");}
    @AfterAll static void stop(){if(jobs!=null)jobs.stop();}
    @Test void ddlRepeatableAndIndexesExist(){
        assertEquals(1,db.queryForObject("select count(*) from asset_history_migration where migration_id='V001__net_equity_history'",Integer.class));
        for(String table:AssetEquityStore.TABLES){
            assertEquals("tenant_id,user_id,basis_version,bucket_start",db.queryForObject("select group_concat(column_name order by seq_in_index) from information_schema.statistics where table_schema=DATABASE() and table_name=? and index_name='PRIMARY'",String.class,table));
            String index=table.equals("asset_history_1m")?"ix_equity_minute_batch":"ix_equity_"+table.substring("asset_history_".length())+"_batch";
            assertEquals("basis_version,bucket_start,user_id",db.queryForObject("select group_concat(column_name order by seq_in_index) from information_schema.statistics where table_schema=DATABASE() and table_name=? and index_name=?",String.class,table,index));
        }
    }
    @Test void allExistingUsersIncludingZeroAndCostOptionsAreSampledOnceConcurrently() throws Exception {
        users(100,101,102);db.update("insert into asset_account(tenant_id,user_id,coin,available,frozen,row_version) values(2,100,'FUND',900,100,0),(2,101,'OPTION',100,50,0)");
        db.update("insert into option_order(tenant_id,user_id,status,amount,direction,symbol,created_at,updated_at,row_version) values(2,101,'TRADING',50,'UP','EQUITY_INPUT',UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)");
        db.update("insert into financial_product(tenant_id,id,name,daily_yield_rate,rental_fee,min_purchase,max_purchase,term_days,created_at,updated_at) values(2,100,'equity synthetic input',0.01,0,1,1000,30,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        db.update("insert into financial_order(tenant_id,id,user_id,product_id,product_name,purchase_amount,daily_yield_rate,daily_yield,total_yield,term_days,purchase_time,created_at,updated_at,row_version) values(2,100,100,100,'equity synthetic input',1000,0.01,10,110,30,UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)");
        db.update("insert into financial_yield_record(tenant_id,user_id,order_id,product_id,product_name,status,daily_yield,cumulative_yield,yield_date,created_at,updated_at,row_version) values(2,100,100,100,'equity synthetic input','PENDING',10,10,'2026-09-01',UTC_TIMESTAMP(),UTC_TIMESTAMP(),0),(2,100,100,100,'equity synthetic input','PAID',100,110,'2026-09-02',UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)");
        ExecutorService threads=Executors.newFixedThreadPool(2);
        try{Future<?> a=threads.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(2L)){jobs.capture();}}),b=threads.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(2L)){jobs.capture();}});a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);}finally{threads.shutdownNow();}
        for(long user:new long[]{100,101,102})assertEquals(1,db.queryForObject("select count(*) from asset_history_1m where tenant_id=2 and user_id=?",Integer.class,user));
        AssetEquityValuationTest.equal("1010",db.queryForObject("select net_equity from asset_history_1m where tenant_id=2 and user_id=100",BigDecimal.class));
        AssetEquityValuationTest.equal("150",db.queryForObject("select net_equity from asset_history_1m where tenant_id=2 and user_id=101",BigDecimal.class));
        AssetEquityValuationTest.equal("0",db.queryForObject("select net_equity from asset_history_1m where tenant_id=2 and user_id=102",BigDecimal.class));
        String evidence=db.queryForObject("select valuation_evidence from asset_history_1m where tenant_id=2 and user_id=101",String.class);assertTrue(evidence.contains("option_principal_cost_not_fair_value"));
        jobs.aggregate();
        for(int level=1;level<=3;level++)
            assertEquals(0,db.queryForObject("select count(*) from "+AssetEquityStore.TABLES[level]+" where tenant_id=2 and user_id=101 and finalized=0",Integer.class));
    }
    @Test @Order(1) void hierarchicalRecoveryNullsIdempotenceAndBoundaryClipping(){
        // A two-day-old UTC bucket is complete even before the 00:03:20 daily run.
        users(200);long start=AssetHistoryBucket.floor(System.currentTimeMillis(),86400000)-2*86400000L;
        store.locked("fixture",s->{
            EquityValuationService.Batch batch=new EquityValuationService.Batch();List<EquityValuationService.Value> values=new ArrayList<>();
            for(int i=0;i<1440;i++){EquityValuationService.Value v=new EquityValuationService.Value(200,start+i*60000+5000);v.add("wallet_balance",BigDecimal.valueOf(i==10?9000:i==11?-900:i));if(i==12)v.missing("contract_unrealized_pnl","QUOTE_STALE");v.finish();values.add(v);}
            store.saveMinutes(s.db,batch,values);
        });
        jobs.aggregate();jobs.aggregate();
        Map<String,Object> day=db.queryForMap("select * from asset_history_1d where tenant_id=2 and user_id=200 and bucket_start=?",start);
        AssetEquityValuationTest.equal("9000",(BigDecimal)day.get("high_value"));AssetEquityValuationTest.equal("-900",(BigDecimal)day.get("low_value"));assertEquals(start+605000,((Number)day.get("high_at")).longValue());
        assertEquals(1439,((Number)day.get("valid_sample_count")).intValue());assertEquals(1,((Number)day.get("invalid_sample_count")).intValue());assertEquals("PARTIAL",day.get("quality"));
        assertEquals(1440,db.queryForObject("select count(*) from asset_history_1m where tenant_id=2 and user_id=200",Integer.class));
        List<AssetHistoryBucket> clipped=store.window(db,3,200,start+37*60000,start+86400000,System.currentTimeMillis());
        AssetHistoryBucket all=new AssetHistoryBucket(200,start+37*60000,start+86400000);clipped.forEach(all::merge);AssetEquityValuationTest.equal("1439",all.high);AssetEquityValuationTest.equal("37",all.low);
        AssetHistoryBucket stale=new AssetHistoryBucket(200,start,start+86400000);stale.merge(AssetHistoryRollupTest.sample(start,"99999"));store.saveBucket(db,3,stale,System.currentTimeMillis());
        AssetEquityValuationTest.equal("9000",db.queryForObject("select high_value from asset_history_1d where tenant_id=2 and user_id=200 and bucket_start=?",BigDecimal.class,start));
        assertEquals(0,db.queryForObject("select count(*) from asset_history_1m where tenant_id=2 and user_id=200 and bucket_start>=?",Integer.class,start+86400000));
    }
    @Test void closingSnapshotKeepsActualTimeRejectsLateAndHidesCurrentParents(){
        users(600);long boundary=AssetHistoryBucket.floor(System.currentTimeMillis(),60000);
        EquityValuationService.Value v=new EquityValuationService.Value(600,boundary+1234);
        v.add("wallet_balance",new BigDecimal("75"));v.finish();
        store.saveMinutes(db,new EquityValuationService.Batch(),Collections.singletonList(v),boundary);
        Map<String,Object> row=db.queryForMap("select bucket_start,observed_at from asset_history_1m where tenant_id=2 and user_id=600");
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
        users(300);long t=AssetHistoryBucket.floor(System.currentTimeMillis(),60000)-600000;
        for(String amount:Arrays.asList("42","999"))store.locked("fixture",s->{EquityValuationService.Value v=new EquityValuationService.Value(300,t+5000);v.add("wallet_balance",new BigDecimal(amount));v.finish();store.saveMinutes(s.db,new EquityValuationService.Batch(),Collections.singletonList(v));});
        AssetEquityValuationTest.equal("42",db.queryForObject("select first_positive from asset_history_baseline where tenant_id=2 and user_id=300",BigDecimal.class));
        db.update("insert into asset_history_baseline(tenant_id,user_id,basis_version,capture_from,first_positive,first_positive_at) values(2,300,'wallet_balance_v1',1,999,1)");
        AssetEquityHistoryService api=new AssetEquityHistoryService(store,valuation);Map<String,Object> opening=api.opening(db,300,t-100000,System.currentTimeMillis());assertEquals("EARLIEST_POSITIVE_NET_EQUITY",opening.get("source"));AssetEquityValuationTest.equal("42",new BigDecimal(opening.get("value").toString()));assertNull(api.opening(db,999999,t,System.currentTimeMillis()));
    }
    @Test void rollbackCoversDataAndCursorAndLockIsReleased(){
        assertThrows(IllegalStateException.class,()->store.locked("failure",s->{store.progress(s.db,"failure",123,9,1);throw new IllegalStateException("fixture");}));
        assertEquals(0,db.queryForObject("select count(*) from asset_history_job_state where tenant_id=2 and task_name='failure'",Integer.class));assertEquals(1,db.queryForObject("select is_free_lock('tenant_2_equity_v1_failure')",Integer.class));
        store.locked("failure",s->store.progress(s.db,"failure",124,10,1));assertEquals(124,store.state(db,"failure",0)[0]);
    }
    @Test void liveApiUsesCorrespondingTablesNoLegacyAndNoFuture(){
        users(400,401);db.update("insert into asset_account(tenant_id,user_id,coin,available,frozen,row_version) values(2,400,'FUND',-5,0,0),(2,401,'FUND',999,0,0)");db.update("insert into asset_snapshot(tenant_id,id,user_id,total,captured_at) values(2,400,400,99999,1)");
        AssetEquityHistoryService api=new AssetEquityHistoryService(store,valuation);
        String[] ranges={"1D","1W","1M","1Y"};for(int i=0;i<4;i++){
            Map<String,Object> r=api.history(400L,ranges[i]);assertEquals(AssetEquityStore.TABLES[i],r.get("sourceTable"));assertEquals(AssetEquityStore.INTERVALS[i],r.get("intervalMs"));assertEquals(2,r.get("schemaVersion"));AssetEquityValuationTest.equal("-5",new BigDecimal(r.get("total").toString()));assertNull(r.get("incomePercent"));assertTrue(((List<?>)r.get("points")).isEmpty());
        }
    }
    @Test void repeatableReadDoesNotMixWalletBeforeAndAfterSettlement(){
        users(500);db.update("insert into asset_account(tenant_id,user_id,coin,available,frozen,row_version) values(2,500,'FUND',1,0,0)");
        store.read(snapshot->{assertEquals(1,snapshot.queryForObject("select available from asset_account where tenant_id=2 and user_id=500",Integer.class));db.update("update asset_account set available=2 where tenant_id=2 and user_id=500");assertEquals(1,snapshot.queryForObject("select available from asset_account where tenant_id=2 and user_id=500",Integer.class));return null;});
        assertEquals(2,db.queryForObject("select available from asset_account where tenant_id=2 and user_id=500",Integer.class));
    }
}
