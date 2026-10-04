package com.gtcfesk.exchange.insights;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** Execute the delivered incremental SQL in a disposable MySQL-mode H2 schema. */
class CalendarMigrationTest {
    @Test void additiveDdlAndTenantEnvironmentReminderUniqueKeys()throws Exception{
        try(Connection c=DriverManager.getConnection("jdbc:h2:mem:calendar_migration;MODE=MySQL","sa","");Statement s=c.createStatement()){
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026100301__economic_calendar.sql"));
            try(ResultSet rows=s.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME LIKE 'CALENDAR_%'")){assertTrue(rows.next());assertEquals(5,rows.getInt(1));}
            String values="(tenant_id,environment,event_id,metric,status,published,manual_lock,data_json,updated_at,row_version) VALUES(%s,'%s','aaaaaaaa-aaaa-3aaa-8aaa-aaaaaaaaaaaa','CORE_PCE_MOM','SCHEDULED',true,false,'{}',CURRENT_TIMESTAMP,0)";
            s.executeUpdate("INSERT INTO calendar_event "+String.format(values,1,"REAL"));assertThrows(SQLException.class,()->s.executeUpdate("INSERT INTO calendar_event "+String.format(values,1,"REAL")));
            s.executeUpdate("INSERT INTO calendar_event "+String.format(values,2,"REAL"));s.executeUpdate("INSERT INTO calendar_event "+String.format(values,1,"DEMO"));
            String reminder="INSERT INTO calendar_reminder(tenant_id,environment,user_id,event_id,lead_minutes,timezone,enabled,updated_at,row_version) VALUES(1,'REAL',7,'aaaaaaaa-aaaa-3aaa-8aaa-aaaaaaaaaaaa',15,'Asia/Singapore',true,CURRENT_TIMESTAMP,0)";
            s.executeUpdate(reminder);assertThrows(SQLException.class,()->s.executeUpdate(reminder));s.executeUpdate(reminder.replace("'REAL'","'DEMO'"));s.executeUpdate(reminder.replace("VALUES(1,","VALUES(2,"));
        }
    }
}
