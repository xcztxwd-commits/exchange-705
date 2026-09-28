package com.gtcfesk.exchange.user;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class AssetEquityCarryTest {
    @Test void carrySeedIsNearestValidEarlierObservationWithNoAgeLimitAndNoWrites(){
        EmbeddedDatabase db=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        try {
            JdbcTemplate jdbc=new JdbcTemplate(db);
            jdbc.execute("create table asset_history_1m(user_id bigint,basis_version varchar(40),bucket_start bigint,observed_at bigint,net_equity decimal(32,16))");
            jdbc.update("insert into asset_history_1m values(1,'net_equity_v1',0,60000,-5),(1,'net_equity_v1',60000,120000,0),(1,'net_equity_v1',120000,180000,null),(2,'net_equity_v1',180000,240000,999),(1,'other',180000,240000,999),(1,'net_equity_v1',900000000,900060000,8)");
            jdbc.execute("alter table asset_history_1m add column effective_at bigint null");
            AssetEquityHistoryService service=new AssetEquityHistoryService(null,null);
            Map<String,Object> seed=service.carryIn(jdbc,1,800000000);
            assertEquals(120000L,seed.get("time"));
            assertEquals(0,new java.math.BigDecimal(seed.get("value").toString()).signum());
            assertNull(service.carryIn(jdbc,1,60000));
            assertNull(service.carryIn(jdbc,99,800000000));
            assertEquals(60000L,service.carryIn(jdbc,1,120000).get("time"));
            assertEquals(6,jdbc.queryForObject("select count(*) from asset_history_1m",Integer.class));
        } finally {db.shutdown();}
    }
}
