package com.gtcfesk.exchange.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.ExchangeBackendApplication;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.user.FinancialYieldService;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Test-only actual process death. No production fault endpoint or replacement financial service. */
public final class FinancialCrashChild {
    private static final ThreadLocal<Boolean> ACTIVE=ThreadLocal.withInitial(()->false);
    private static String mode;private static Path marker;private static long tenant;
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("mode, operation, tenant, marker required");
        mode=args[0];if(!Set.of("BEFORE","AFTER","RECOVER").contains(mode)||!Set.of("ACCRUE","PAY").contains(args[1]))throw new IllegalArgumentException("Unknown owned child action");
        tenant=Long.parseLong(args[2]);marker=Paths.get(args[3]).toAbsolutePath();
        Path definition=Paths.get(System.getProperty("joint.mysql.fixture")).toAbsolutePath();
        if(!marker.getParent().equals(definition.getParent())||Files.exists(marker))throw new IllegalArgumentException("Fresh private child marker required");
        Map<String,Object> properties=new LinkedHashMap<>();
        JointFundingEnabledMySqlIT.configured((key,supplier)->properties.put(key,supplier.get()));
        properties.put("logging.level.root","ERROR");properties.put("spring.main.banner-mode","off");
        SpringApplication app=new SpringApplication(ExchangeBackendApplication.class,Observation.class);
        // Standalone Boot does not get SpringBootTest's test-class exclusion customizer.
        // Exclude every compiled test class from component scanning; only the explicit observation source is admitted.
        final String testLocation=FinancialCrashChild.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
        app.addInitializers(context->context.getBeanFactory().registerSingleton("ownedTestClassExclusion",new org.springframework.boot.context.TypeExcludeFilter(){
            @Override public boolean match(org.springframework.core.type.classreading.MetadataReader reader,org.springframework.core.type.classreading.MetadataReaderFactory factory)throws java.io.IOException {
                return reader.getResource().getURL().toExternalForm().startsWith(testLocation);
            }
        }));
        String[] settings=properties.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new);
        try(ConfigurableApplicationContext context=app.run(settings);TenantContext.Scope scope=TenantContext.open(tenant)){
            FinancialYieldService service=context.getBean(FinancialYieldService.class);
            if(!org.springframework.aop.support.AopUtils.isAopProxy(service))throw new IllegalStateException("Actual financial proxy required");
            ACTIVE.set(true);
            try{if("ACCRUE".equals(args[1]))service.calculateDailyYield();else service.payoutAllPendingYields();}
            finally{ACTIVE.remove();}
            if(!"RECOVER".equals(mode))throw new IllegalStateException("Required actual money COMMIT not reached");
            seal(Map.of("phase","SUCCESSOR_RETURNED","tenant",tenant,"pid",ProcessHandle.current().pid()));
        }
    }
    private static void seal(Map<String,Object> value)throws Exception {
        byte[] data=new ObjectMapper().writeValueAsBytes(value);
        try(FileChannel channel=FileChannel.open(marker,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            ByteBuffer buffer=ByteBuffer.wrap(data);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
    }
    @TestConfiguration static class Observation {
        // This probe invokes the real money services explicitly, not unrelated wall-clock jobs.
        // Keep their service beans intact while preventing background history writes during the crash window.
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor noUnrelatedSchedules(){return factory->{
            org.springframework.beans.factory.support.BeanDefinitionRegistry registry=(org.springframework.beans.factory.support.BeanDefinitionRegistry)factory;
            String name=org.springframework.scheduling.config.TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
            if(!registry.containsBeanDefinition(name))throw new IllegalStateException("Expected real scheduling registrar");
            registry.removeBeanDefinition(name);
        };}
        @Bean static BeanPostProcessor abruptOwnedDeath(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){return bean instanceof DataSource&&!(bean instanceof Observed)?new Observed((DataSource)bean):bean;}
        };}
    }
    private static final class Observed extends DelegatingDataSource {
        Observed(DataSource source){super(source);}
        @Override public Connection getConnection()throws SQLException {
            Connection actual=super.getConnection();long id;
            try(Statement s=actual.createStatement();ResultSet r=s.executeQuery("SELECT CONNECTION_ID()")){if(!r.next())throw new SQLException("Connection identity missing");id=r.getLong(1);}
            final int[] writes={0};final long connection=id;
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                boolean moneyCommit=ACTIVE.get()&&m.getName().equals("commit")&&writes[0]>0;
                if(moneyCommit&&"BEFORE".equals(mode))die(actual,connection,writes[0],91);
                Object value=call(actual,m,a);
                if(moneyCommit&&"AFTER".equals(mode))die(actual,connection,writes[0],92);
                if(m.getName().equals("commit")||m.getName().equals("rollback"))writes[0]=0;
                if(value instanceof PreparedStatement){String sql=((String)a[0]).trim().toLowerCase(Locale.ROOT);PreparedStatement statement=(PreparedStatement)value;
                    Class<?> type=value instanceof CallableStatement?CallableStatement.class:PreparedStatement.class;
                    return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(q,n,b)->{
                        Object result=call(statement,n,b);
                        if(ACTIVE.get()&&n.getName().startsWith("execute")&&(sql.startsWith("insert ")||sql.startsWith("update ")||sql.startsWith("delete ")))writes[0]++;
                        return result;
                    });
                }return value;
            });
        }
    }
    private static void die(Connection connection,long id,int writes,int code)throws Exception {
        if(connection.getAutoCommit())throw new IllegalStateException("Actual physical transaction required");
        seal(Map.of("phase",mode+"_PHYSICAL_COMMIT","tenant",tenant,"pid",ProcessHandle.current().pid(),"connectionId",id,"moneyStatements",writes,"isolation",connection.getTransactionIsolation(),"exitCode",code));
        Runtime.getRuntime().halt(code);
    }
    private static Object call(Object target,Method method,Object[] args)throws Throwable {try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
}
