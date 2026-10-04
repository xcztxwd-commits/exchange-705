package com.gtcfesk.exchange.insights;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class TraderMigrationTest {
    @Test void additiveSchemaConstraintsAndTenantEnvironmentParents()throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:h2:mem:trader_migration;MODE=MySQL","sa","");Statement s=c.createStatement()){
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026100303__curated_traders.sql"));
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME LIKE 'TRADER_%'")){assertTrue(r.next());assertEquals(4,r.getInt(1));}
            String p="INSERT INTO trader_profile(tenant_id,environment,trader_id,source_type,status,name,currency,updated_at,data_json) VALUES(%s,'%s','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','DEMO','DRAFT','TEST_ONLY','USD',CURRENT_TIMESTAMP,'{}')";
            s.executeUpdate(String.format(p,1,"DEMO"));assertThrows(SQLException.class,()->s.executeUpdate(String.format(p,1,"DEMO")));s.executeUpdate(String.format(p,2,"DEMO"));s.executeUpdate(String.format(p,1,"REAL"));
            String e="INSERT INTO trader_equity(tenant_id,environment,trader_id,point_id,point_at,net_asset,currency) VALUES(%s,'%s','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','2026-09-01',0.000000000000000001,'USD')";
            s.executeUpdate(String.format(e,1,"DEMO"));assertThrows(SQLException.class,()->s.executeUpdate(String.format(e,1,"DEMO")));assertThrows(SQLException.class,()->s.executeUpdate(String.format(e,3,"DEMO")));s.executeUpdate(String.format(e,2,"DEMO"));s.executeUpdate(String.format(e,1,"REAL"));
            try(ResultSet r=s.executeQuery("SELECT net_asset FROM trader_equity WHERE tenant_id=1 AND environment='DEMO'")){assertTrue(r.next());assertEquals("0.000000000000000001",r.getBigDecimal(1).stripTrailingZeros().toPlainString());}
            assertThrows(SQLException.class,()->s.executeUpdate("DELETE FROM trader_profile WHERE tenant_id=1 AND environment='DEMO'"));
        }
    }
}
