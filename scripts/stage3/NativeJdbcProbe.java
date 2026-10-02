import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Read-only replacement for the historical port-pinned conditional DATETIME test. */
class NativeJdbcProbe {
    public static void main(String[] args) throws Exception {
        Properties p=new Properties();
        try(java.io.Reader reader=Files.newBufferedReader(Paths.get(args[0]))) {p.load(reader);}
        String url=p.getProperty("spring.datasource.url");
        if(!url.startsWith("jdbc:mysql://127.0.0.1:33718/mt705_probe_stage3_real_20261002_232106?"))throw new IllegalStateException("Wrong stage3 target");
        try(Connection c=DriverManager.getConnection(url,p.getProperty("spring.datasource.username"),p.getProperty("spring.datasource.password"));
            Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT CAST('2024-01-01 00:00:00.123456' AS DATETIME(6)) AS created_at")) {
            if(!r.next())throw new IllegalStateException("Missing native row");
            Object v=r.getObject(1);
            Instant instant=v instanceof Timestamp?((Timestamp)v).toInstant():v instanceof LocalDateTime?((LocalDateTime)v).toInstant(ZoneOffset.UTC):null;
            if(!Instant.parse("2024-01-01T00:00:00.123456Z").equals(instant))throw new IllegalStateException("Precision or type mismatch");
            System.out.println("PASS native JDBC "+v.getClass().getName()+" UTC microseconds; SELECT only");
        }
    }
}
