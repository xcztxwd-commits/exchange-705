package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.tenant.DedicatedMysqlFixture;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** True process crash of the actual SOURCE projector. This does not substitute scheduler/service/fence/SQL. */
public final class SourceProjectionCrashChild {
    private static String point;private static Path marker;private static long symbol,tenant;
    private static final List<Map<String,Object>> writes=new ArrayList<>();
    private static final Set<Long> connections=new LinkedHashSet<>();
    private static int minute;
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("point, tenant, symbol, fresh marker required");
        point=args[0];tenant=Long.parseLong(args[1]);symbol=Long.parseLong(args[2]);marker=Paths.get(args[3]).toAbsolutePath().normalize();
        if(!Arrays.asList("PROGRESS_INSERT","HORIZON_UPDATE","GENERATION_UPDATE","MINUTE_1","MINUTE_2","PROGRESS_PUBLISH","DIRTY_CURSOR","BEFORE_COMMIT","AFTER_COMMIT","RECOVER").contains(point))throw new IllegalArgumentException("Unknown projection point");
        Path definition=Paths.get(System.getProperty("joint.s4process.fixture")).toAbsolutePath().normalize();
        if(!marker.getParent().equals(definition.getParent().resolve("s4-process-raw"))||Files.exists(marker))throw new IllegalArgumentException("Fresh owned marker required");
        DataSource data=new Observed(DedicatedMysqlFixture.fromProperty("joint.s4process.fixture"));
        JdbcTemplate db=new JdbcTemplate(data);DataSourceTransactionManager manager=new DataSourceTransactionManager(data);
        require404(db);
        try(TenantContext.Scope scope=TenantContext.open(tenant)){
            ControlHistoryStore store=new ControlHistoryStore(db,manager);SourceHistoryProjector projector=new SourceHistoryProjector(store,manager);
            Map<String,Object> runtime=db.queryForMap("SELECT owner_id,writer_generation,control_revision FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",tenant,symbol);
            boolean changed=projector.project(symbol,(String)runtime.get("owner_id"),((Number)runtime.get("writer_generation")).longValue(),((Number)runtime.get("control_revision")).longValue());
            if(!point.equals("RECOVER"))throw new IllegalStateException("Actual projection point was not reached: "+point);
            Map<String,Object> result=state("SUCCESSOR_RETURNED");result.put("changed",changed);seal(result);
        }
    }
    static void require404(JdbcTemplate db){
        if(db.queryForObject("SELECT COUNT(*) FROM tenant_schema_version WHERE version=2026100404 AND minimum_application_epoch=2026100404 AND business_activation_ready=0",Integer.class)!=1
            ||db.queryForObject("SELECT MAX(minimum_application_epoch) FROM tenant_schema_version",Long.class)!=2026100404L
            ||db.queryForObject("SELECT SUM(business_activation_ready) FROM tenant_schema_version",Integer.class)!=0)
            throw new IllegalStateException("Exact inactive formal0404 required");
    }
    private static Map<String,Object> state(String phase){Map<String,Object> result=new LinkedHashMap<>();result.put("phase",phase);result.put("point",point);result.put("tenant",tenant);result.put("symbol",symbol);result.put("pid",ProcessHandle.current().pid());result.put("writes",writes);result.put("physicalWriteConnections",connections);return result;}
    private static void seal(Map<String,Object> value)throws Exception {
        byte[] bytes=new ObjectMapper().writeValueAsBytes(value);
        try(FileChannel channel=FileChannel.open(marker,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
    }
    private static void die(Connection actual,long id,String at)throws Exception {
        if(actual.getAutoCommit()||writes.isEmpty()||connections.size()!=1)throw new IllegalStateException("One physical projection transaction required");
        Map<String,Object> result=state("ABRUPT_PROCESS_DEATH");result.put("reached",at);result.put("connectionId",id);result.put("autoCommit",actual.getAutoCommit());result.put("isolation",actual.getTransactionIsolation());result.put("exitCode",73);seal(result);Runtime.getRuntime().halt(73);
    }
    private static final class Observed extends DelegatingDataSource {
        Observed(DataSource source){super(source);}
        @Override public Connection getConnection()throws SQLException{
            Connection actual=super.getConnection();final long id;
            try(Statement s=actual.createStatement();ResultSet r=s.executeQuery("SELECT CONNECTION_ID()")){if(!r.next())throw new SQLException("Connection identity missing");id=r.getLong(1);}
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                boolean commit=m.getName().equals("commit")&&!writes.isEmpty();
                if(commit&&point.equals("BEFORE_COMMIT"))die(actual,id,"BEFORE_COMMIT");
                Object value=call(actual,m,a);
                if(commit&&point.equals("AFTER_COMMIT"))die(actual,id,"AFTER_COMMIT");
                if(value instanceof PreparedStatement){PreparedStatement statement=(PreparedStatement)value;String sql=((String)a[0]).trim();
                    Class<?> type=value instanceof CallableStatement?CallableStatement.class:PreparedStatement.class;
                    return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(q,n,b)->{
                        Object result=call(statement,n,b);
                        if(n.getName().startsWith("execute")&&result instanceof Number&&((Number)result).longValue()>0){
                            String stage=stage(sql);if(stage!=null){
                                connections.add(id);Map<String,Object> event=new LinkedHashMap<>();event.put("stage",stage);event.put("sql",sql);event.put("connectionId",id);event.put("affectedRows",result);event.put("atNanos",System.nanoTime());writes.add(event);
                                if(point.equals(stage))die(actual,id,stage);
                            }
                        }return result;
                    });
                }return value;
            });
        }
    }
    private static String stage(String sql){
        if(sql.startsWith("INSERT IGNORE INTO s4_history_projection_progress"))return "PROGRESS_INSERT";
        if(sql.startsWith("UPDATE s4_history_projection_progress SET stop_at="))return "HORIZON_UPDATE";
        if(sql.startsWith("UPDATE s4_history_projection_progress SET generation="))return "GENERATION_UPDATE";
        if(sql.startsWith("INSERT INTO s4_history_projection_minute"))return "MINUTE_"+(++minute);
        if(sql.startsWith("UPDATE s4_history_projection_progress SET fact_version="))return "PROGRESS_PUBLISH";
        if(sql.startsWith("UPDATE market_engine_runtime SET source_dirty_from="))return "DIRTY_CURSOR";
        return null;
    }
    private static Object call(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}}
}
