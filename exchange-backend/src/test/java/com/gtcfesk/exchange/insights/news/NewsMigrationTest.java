package com.gtcfesk.exchange.insights.news;
import org.junit.jupiter.api.Test;import org.springframework.core.io.ClassPathResource;import org.springframework.jdbc.datasource.init.ScriptUtils;import java.sql.*;import static org.junit.jupiter.api.Assertions.*;
class NewsMigrationTest {
    @Test void additiveTablesAndTenantEnvironmentUniqueKeys()throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:h2:mem:news_migration;MODE=MySQL","sa","");Statement s=c.createStatement()){
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026100302__external_news.sql"));
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME LIKE 'NEWS_%'")){assertTrue(r.next());assertEquals(4,r.getInt(1));}
            String sql="INSERT INTO news_article(tenant_id,environment,article_id,source_id,category,language,discovered_at,updated_at,hidden,data_json,upstream_hash) VALUES(%s,'%s','aaaaaaaa-aaaa-3aaa-8aaa-aaaaaaaaaaaa','FED','MACRO','en',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,false,'{}','test')";
            s.executeUpdate(String.format(sql,1,"REAL"));assertThrows(SQLException.class,()->s.executeUpdate(String.format(sql,1,"REAL")));s.executeUpdate(String.format(sql,2,"REAL"));s.executeUpdate(String.format(sql,1,"DEMO"));
            String source="INSERT INTO news_source_setting(tenant_id,environment,source_id,enabled,license_reviewed) VALUES(1,'REAL','FED',false,true)";s.executeUpdate(source);assertThrows(SQLException.class,()->s.executeUpdate(source));
        }
    }
}
