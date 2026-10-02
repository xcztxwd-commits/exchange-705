package com.gtcfesk.exchange.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.AdminUserService;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.support.*;
import com.gtcfesk.exchange.control.TenantPolicyService;
import java.nio.file.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.persistence.*;
import javax.sql.DataSource;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Incremental, synthetic-row MySQL checks against the accepted JAR. No application jobs start.
 * Supply a newly owned clone connection, the table manifest and a public JSON output path.
 * The runner verifies Docker ownership; this probe independently pins URL, database and UUID.
 * Settings/feature collaborators are fixed fixtures; SQL, repositories and transactions are real.
 */
public final class OrmCloseoutProbe {
    static DataSource source;
    static EntityManagerFactory factory;
    static AnnotationConfigApplicationContext context;
    static TransactionTemplate tx;
    static JdbcTemplate db;
    static long a,b,menu;
    static final List<String> sql=new ArrayList<>();
    static final List<Map<String,Object>> results=new ArrayList<>();
    static final List<String> tables=new ArrayList<>();
    static final ObjectMapper mapper=new ObjectMapper();
    static int assertions;
    static final long fixtureBase=System.currentTimeMillis()*1000L;
    static final class ExpectedRollback extends RuntimeException {}
    public static final class Observer implements org.hibernate.resource.jdbc.spi.StatementInspector {
        public String inspect(String statement) {
            String s=statement.toLowerCase(Locale.ROOT);
            if(s.startsWith("update ")||s.startsWith("delete ")||s.startsWith("insert "))sql.add(s);
            return statement;
        }
    }
    @Configuration
    @EnableJpaRepositories(basePackages="com.gtcfesk.exchange",repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class)
    public static class JpaOnly {
        @Bean public DataSource dataSource(){return source;}
        @Bean public LocalContainerEntityManagerFactoryBean entityManagerFactory(){
            LocalContainerEntityManagerFactoryBean bean=new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(source);bean.setPackagesToScan("com.gtcfesk.exchange");bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","validate");
            p.setProperty("hibernate.dialect","org.hibernate.dialect.MySQL57Dialect");
            p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
            p.setProperty("hibernate.session_factory.statement_inspector",Observer.class.getName());bean.setJpaProperties(p);return bean;
        }
        @Bean public JpaTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
    }
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);assertions++;}
    static EntityManager em(){return Objects.requireNonNull(EntityManagerFactoryUtils.getTransactionalEntityManager(factory));}
    static <T>T repo(Class<T> c){return context.getBean(c);}
    static void transaction(Runnable action){tx.execute(s->{action.run();return null;});}
    static RuntimeException denied(Runnable action){try{action.run();}catch(RuntimeException e){assertions++;return e;}throw new AssertionError("Expected failure");}
    static void field(Object target,String name,Object value)throws Exception{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static List<String> snapshot(long tenant){
        List<String> rows=new ArrayList<>();
        for(String table:tables)db.query("SELECT * FROM `"+table+"` WHERE tenant_id=?",r->{
            java.security.MessageDigest digest;
            try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}
            for(int i=1;i<=r.getMetaData().getColumnCount();i++){
                byte[] v=r.getBytes(i);digest.update((byte)(v==null?0:1));
                if(v!=null){digest.update(java.nio.ByteBuffer.allocate(4).putInt(v.length).array());digest.update(v);}
            }
            rows.add(table+":"+Base64.getEncoder().encodeToString(digest.digest()));
        },tenant);
        Collections.sort(rows);return rows;
    }
    static int count(String table,long tenant){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Integer.class,tenant);}
    static void seed(boolean inbox){
        a=fixtureBase+results.size()*10;b=a+1;
        check(db.queryForObject("SELECT COUNT(*) FROM tenant WHERE id IN (?,?)",Integer.class,a,b)==0,"New fixture IDs only");
        transaction(()->{
            for(long t:new long[]{a,b}){
                db.update("INSERT INTO tenant(id,code,name,status,created_at) VALUES(?,?,'Closeout synthetic','MAINTENANCE',UTC_TIMESTAMP())",t,"closeout-"+t);
                db.update("INSERT INTO user_account(id,tenant_id,email,phone,password_hash,status,row_version) VALUES(?,?,?,'+10000000000','NOT_A_LOGIN_PASSWORD','normal',0)",t,t,"closeout-"+t+"@example.invalid");
                db.update("INSERT INTO asset_account(id,tenant_id,user_id,coin,row_version) VALUES(?,?,?,'CLOSEOUT',0)",t,t,t);
                db.update("INSERT INTO admin_role(id,tenant_id,role_name,role_code) VALUES(?,?,'Closeout','CLOSEOUT')",t,t);
                db.update("INSERT INTO admin_role_menu(tenant_id,role_id,menu_id) VALUES(?,?,?)",t,t,menu);
                db.update("INSERT INTO user_action(tenant_id,user_id,menu_id,action_code) VALUES(?,?,?,'view')",t,t,menu);
                db.update("INSERT INTO user_menu(tenant_id,user_id,menu_id) VALUES(?,?,?)",t,t,menu);
                db.update("INSERT INTO user_bank_card(tenant_id,user_id,currency,bank_name,recipient_name,recipient_account,created_at,updated_at) VALUES(?,?,'USD','SYNTHETIC','SYNTHETIC','NOT_AN_ACCOUNT',UTC_TIMESTAMP(),UTC_TIMESTAMP())",t,t);
                db.update("INSERT INTO user_digital_address(tenant_id,user_id,currency,network,address,created_at,updated_at) VALUES(?,?,'USDT','TEST','NOT_AN_ADDRESS',UTC_TIMESTAMP(),UTC_TIMESTAMP())",t,t);
                if(inbox){
                    db.update("INSERT INTO announcement(id,tenant_id,title,content,language) VALUES(?,?,'Closeout','Synthetic','en')",t,t);
                    db.update("INSERT INTO inbox_letter(tenant_id,user_id,request_id,title,content,created_at) VALUES(?,?,'closeout','Closeout','Synthetic',UTC_TIMESTAMP())",t,t);
                    db.update("INSERT INTO activity_campaign(id,tenant_id,name,status,translations,amount,budget) VALUES(?,?,'Closeout','ACTIVE','{}',0,0)",t,t);
                    db.update("INSERT INTO activity_delivery(id,tenant_id,campaign_id,user_id) VALUES(?,?,?,?)",t,t,t,t);
                }
            }
        });sql.clear();
    }
    static void caseRun(String name,boolean inbox,Runnable test){
        String only=System.getProperty("closeout.only");if(only!=null&&!only.equals(name))return;
        seed(inbox);
        if(name.equals("order-repository-three-bulk-update-entries"))transaction(()->{for(long t:new long[]{a,b}){
            db.update("INSERT INTO contract_order(id,tenant_id,user_id,created_at,updated_at,quantity,side,status,symbol,type,limit_match_enabled,row_version,price) VALUES(?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP(),1,'BUY','PENDING','CLOSEOUT','LIMIT',1,0,10)",t,t,t);
            db.update("INSERT INTO option_order(id,tenant_id,user_id,amount,created_at,updated_at,direction,status,symbol,row_version) VALUES(?,?,?,1,UTC_TIMESTAMP(),UTC_TIMESTAMP(),'UP','CLOSED','CLOSEOUT',0)",t,t,t);
        }});
        int start=assertions;List<String> foreign=snapshot(b);
        try{test.run();check(foreign.equals(snapshot(b)),"Every B column unchanged");check(TenantContext.currentTenantId()==null,"Tenant context restored");
            Map<String,Object> result=new LinkedHashMap<>();result.put("name",name);result.put("status","PASS");result.put("assertions",assertions-start);
            result.put("foreignAllColumnsUnchanged",true);result.put("observedDml",new ArrayList<>(new LinkedHashSet<>(sql)));results.add(result);
        }finally{SecurityContextHolder.clearContext();TenantContext.clear();}
    }
    static void own(Runnable action){try(TenantContext.Scope ignored=TenantContext.open(a)){action.run();}}
    static void rollbackProof(Runnable action){
        List<String> before=snapshot(a);own(()->{RuntimeException e=denied(()->transaction(()->{action.run();throw new ExpectedRollback();}));check(e instanceof ExpectedRollback,"Expected application failure reached after writes");});
        check(before.equals(snapshot(a)),"Failure rolls back every A column");
    }
    static AdminUserService admin(){
        try{AdminUserService s=new AdminUserService();field(s,"userAccountRepository",repo(UserAccountRepository.class));field(s,"assetAccountRepository",repo(AssetAccountRepository.class));field(s,"entityManager",SharedEntityManagerCreator.createSharedEntityManager(factory));return s;}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    static SupportSettings enabledSettings(){return new SupportSettings(null,mapper){@Override public Settings get(){Settings s=new Settings();s.inboxEnabled=true;s.mode="internal";return s;}};}
    static void authenticate(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(a),null,Collections.singleton(new SimpleGrantedAuthority("ROLE_USER"))));}
    static UnifiedInboxService inbox(){
        try{UnifiedInboxService s=new UnifiedInboxService(enabledSettings(),new TenantPolicyService(null,null){@Override public boolean featureEnabled(String f){return true;}},mapper);field(s,"em",SharedEntityManagerCreator.createSharedEntityManager(factory));return s;}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    static void checks(){
        caseRun("ordinary-versioned-update-delete-failure-atomicity",false,()->{
            rollbackProof(()->{AssetAccount x=em().find(AssetAccount.class,a);x.setAvailable(BigDecimal.TEN);em().flush();em().remove(x);em().flush();});
            check(sql.stream().anyMatch(s->s.startsWith("update asset_account")&&s.contains("tenant_id="+a)&&s.contains("row_version=?")),"Scoped UPDATE observed");
            check(sql.stream().anyMatch(s->s.startsWith("delete from asset_account")&&s.contains("tenant_id="+a)&&s.contains("row_version=?")),"Scoped DELETE observed");
        });
        caseRun("ordinary-sql-constraint-error-rolls-back-earlier-flush",false,()->{
            List<String> before=snapshot(a);own(()->{
                RuntimeException error=denied(()->transaction(()->{em().find(AssetAccount.class,a).setAvailable(BigDecimal.TEN);em().flush();AssetAccount duplicate=new AssetAccount();duplicate.setUserId(a);duplicate.setCoin("CLOSEOUT");em().persist(duplicate);em().flush();}));
                boolean duplicate=false;for(Throwable t=error;t!=null;t=t.getCause())if(t instanceof SQLException&&((SQLException)t).getErrorCode()==1062)duplicate=true;
                check(duplicate,"Real MySQL duplicate-key error, not unrelated failure");
            });check(before.equals(snapshot(a)),"SQL error leaves no partial writes");
        });
        caseRun("missing-context-dirty-flush-rolls-back-prior-write",false,()->{
            List<String> before=snapshot(a);RuntimeException error=denied(()->transaction(()->{
                AssetAccount x;try(TenantContext.Scope ignored=TenantContext.open(a)){x=em().find(AssetAccount.class,a);x.setAvailable(BigDecimal.ONE);em().flush();}
                x.setAvailable(BigDecimal.TEN);em().flush();
            }));boolean denied=false;for(Throwable t=error;t!=null;t=t.getCause())if(t instanceof org.springframework.security.access.AccessDeniedException)denied=true;
            check(denied,"Missing-context rejection");check(before.equals(snapshot(a)),"Prior flush rolled back");
        });
        caseRun("repository-jpql-user-updates-owner-foreign-rollback",false,()->{
            UserAccountRepository users=repo(UserAccountRepository.class);LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
            denied(()->users.touchActivity(a,now));
            own(()->transaction(()->{check(users.touchActive(b,now,now.plusDays(1))==0,"Foreign touchActive zero rows");check(users.reportPage(b,"HOME","PC",1,now,now.plusDays(1))==0,"Foreign reportPage zero rows");users.touchActivity(b,now);
                check(users.touchActive(a,now,now.plusDays(1))==1,"Owner touchActive");check(users.reportPage(a,"HOME","PC",1,now,now.plusDays(1))==1,"Owner reportPage");users.touchActivity(a,now.plusSeconds(1));}));
            rollbackProof(()->{users.touchActivity(a,now.plusHours(1));check(users.reportPage(a,"ASSETS","MOBILE",2,now.plusHours(1),now.plusDays(1))==1,"Bulk write before rollback");});
        });
        caseRun("repository-jpql-three-delete-entries-and-atomicity",false,()->{
            UserActionRepository actions=repo(UserActionRepository.class);AdminRoleMenuRepository roles=repo(AdminRoleMenuRepository.class);
            denied(()->actions.deleteByUserId(a));denied(()->actions.deleteByUserIdAndMenuId(a,menu));denied(()->roles.deleteByRoleId(a));
            own(()->transaction(()->{actions.deleteByUserId(b);actions.deleteByUserIdAndMenuId(b,menu);roles.deleteByRoleId(b);}));
            rollbackProof(()->{actions.deleteByUserIdAndMenuId(a,menu);roles.deleteByRoleId(a);check(count("user_action",a)==0&&count("admin_role_menu",a)==0,"Actual bulk deletes before failure");});
            own(()->transaction(()->{actions.deleteByUserIdAndMenuId(a,menu);check(count("user_action",a)==0,"Owner menu delete");UserAction x=new UserAction();x.setUserId(a);x.setMenuId(menu);x.setActionCode("view");actions.saveAndFlush(x);actions.deleteByUserId(a);roles.deleteByRoleId(a);}));
            check(count("user_action",a)==0&&count("admin_role_menu",a)==0,"Owner deletes committed");
        });
        caseRun("native-admin-delete-success-and-foreign-rejection",false,()->{
            AdminUserService service=admin();own(()->{RuntimeException e=denied(()->transaction(()->service.deleteUser(b)));check(e instanceof IllegalArgumentException,"Foreign user unavailable");transaction(()->service.deleteUser(a));});
            for(String t:Arrays.asList("user_account","asset_account","user_action","user_menu","user_bank_card","user_digital_address"))check(count(t,a)==0,"Owner delete "+t);
            check(sql.stream().filter(s->s.startsWith("delete from")&&s.contains("user_id = ?")&&s.contains("tenant_id = "+a)).count()==4,"All four native delete templates executed");
        });
        caseRun("native-admin-delete-sql-error-restores-partial-deletes",true,()->{
            AdminUserService service=admin();List<String> before=snapshot(a);
            own(()->{RuntimeException e=denied(()->transaction(()->service.deleteUser(a)));boolean fk=false;for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof SQLException&&((SQLException)t).getErrorCode()==1451)fk=true;check(fk,"Real FK failure after dependent deletes");});
            check(before.equals(snapshot(a)),"Native and ORM partial deletes rolled back");
            check(sql.stream().anyMatch(s->s.startsWith("delete from user_bank_card")),"Failure happened after native writes");
        });
        caseRun("support-read-all-letters-owner-and-rollback",true,()->{
            authenticate();SupportService service=new SupportService(enabledSettings(),null,mapper);try{field(service,"em",SharedEntityManagerCreator.createSharedEntityManager(factory));}catch(Exception e){throw new IllegalStateException(e);}
            rollbackProof(service::readAllLetters);own(()->transaction(service::readAllLetters));
            check(db.queryForObject("SELECT COUNT(*) FROM inbox_letter WHERE tenant_id=? AND read_at IS NOT NULL",Integer.class,a)==1,"Owner letter committed");
        });
        caseRun("unified-inbox-native-insert-select-and-two-bulk-updates",true,()->{
            authenticate();UnifiedInboxService service=inbox();rollbackProof(()->service.readAll("en"));own(()->transaction(()->service.readAll("en")));
            check(count("announcement_receipt",a)==1,"Native receipt inserted");
            check(db.queryForObject("SELECT COUNT(*) FROM inbox_letter WHERE tenant_id=? AND read_at IS NOT NULL",Integer.class,a)==1,"Letter bulk update");
            check(db.queryForObject("SELECT open_count FROM activity_delivery WHERE tenant_id=?",Integer.class,a)==1,"Activity bulk update");
            check(sql.stream().anyMatch(s->s.startsWith("insert into announcement_receipt")&&s.contains("a.tenant_id=?")),"Native INSERT SELECT source scope");
        });
        caseRun("order-repository-three-bulk-update-entries",false,()->{
            List<String> foreign=snapshot(b);ContractOrderRepository contracts=repo(ContractOrderRepository.class);OptionOrderRepository options=repo(OptionOrderRepository.class);LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
            denied(()->transaction(()->contracts.updateDeletion(a,0,now,"fixture")));denied(()->transaction(()->options.updateDeletion(a,0,now,"fixture")));denied(()->contracts.openPendingLimitOrder(a,0,BigDecimal.ONE,now));
            own(()->transaction(()->{check(contracts.updateDeletion(b,0,now,"fixture")==0,"Foreign contract unchanged");check(options.updateDeletion(b,0,now,"fixture")==0,"Foreign option unchanged");check(contracts.openPendingLimitOrder(b,0,BigDecimal.ONE,now)==0,"Foreign limit unchanged");}));
            rollbackProof(()->{check(contracts.updateDeletion(a,0,now,"fixture")==1,"Contract soft delete");check(options.updateDeletion(a,0,now,"fixture")==1,"Option soft delete");});
            own(()->transaction(()->{check(contracts.openPendingLimitOrder(a,0,BigDecimal.ONE,now)==1,"Owner limit fill");check(contracts.openPendingLimitOrder(a,0,BigDecimal.ONE,now)==0,"Stale limit version");check(contracts.updateDeletion(a,1,now,"fixture")==1,"Owner contract delete mark");check(options.updateDeletion(a,0,now,"fixture")==1,"Owner option delete mark");}));
            check(foreign.equals(snapshot(b)),"All foreign order columns unchanged");
        });
        caseRun("force-jdbc-error-stale-rollback-and-resource-release",false,()->{
            org.hibernate.persister.entity.EntityPersister persister=factory.unwrap(SessionFactoryImplementor.class).getMetamodel().entityPersister(AssetAccount.class);
            List<String> before=snapshot(a);String trigger="closeout_force_fail_"+a;
            db.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON asset_account FOR EACH ROW BEGIN IF NEW.tenant_id="+a+" THEN SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,MESSAGE_TEXT='Owned closeout fault'; END IF; END");
            try{own(()->{RuntimeException e=denied(()->transaction(()->{
                repo(UserAccountRepository.class).touchActivity(a,LocalDateTime.now(ZoneOffset.UTC));
                persister.forceVersionIncrement(a,0L,em().unwrap(org.hibernate.engine.spi.SharedSessionContractImplementor.class));
            }));boolean sqlError=false;for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof SQLException&&((SQLException)t).getErrorCode()==1644)sqlError=true;check(sqlError,"Injected MySQL error reached FORCE JDBC path");});}
            finally{db.execute("DROP TRIGGER "+trigger);}
            check(before.equals(snapshot(a)),"FORCE JDBC failure rolled back earlier bulk write");
            own(()->{RuntimeException e=denied(()->transaction(()->{
                repo(UserAccountRepository.class).touchActivity(a,LocalDateTime.now(ZoneOffset.UTC));
                persister.forceVersionIncrement(b,0L,em().unwrap(org.hibernate.engine.spi.SharedSessionContractImplementor.class));
            }));check(e instanceof org.hibernate.StaleObjectStateException,"Foreign FORCE returns stale row, not a different error");});
            check(before.equals(snapshot(a)),"Stale FORCE rolled back earlier bulk write");
            own(()->transaction(()->check(Long.valueOf(1L).equals(persister.forceVersionIncrement(a,0L,em().unwrap(org.hibernate.engine.spi.SharedSessionContractImplementor.class))),"Owner FORCE succeeds after failed statement cleanup")));
            int observed=sql.size();own(()->{RuntimeException e=denied(()->transaction(()->factory.unwrap(SessionFactoryImplementor.class).getMetamodel().entityPersister(Announcement.class).forceVersionIncrement(a,null,em().unwrap(org.hibernate.engine.spi.SharedSessionContractImplementor.class))));check(e instanceof org.hibernate.HibernateException,"Unversioned FORCE explicitly rejected");});
            check(sql.size()==observed,"Unversioned FORCE emitted no DML");
        });
    }
    public static void main(String[] args)throws Exception{
        Map<?,?> c=mapper.readValue(Paths.get(args[0]).toFile(),Map.class);String url=(String)c.get("url");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mt705_stage5_closeout\\?.*"))throw new IllegalArgumentException("Owned loopback closeout clone required");
        source=new DriverManagerDataSource(url,(String)c.get("username"),(String)c.get("password"));db=new JdbcTemplate(source);
        check(c.get("serverUuid").equals(db.queryForObject("SELECT @@server_uuid",String.class)),"Pinned server UUID");check(db.queryForObject("SELECT VERSION()",String.class).startsWith("5.7."),"Real MySQL 5.7");
        tables.addAll(db.queryForList("SELECT TABLE_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='tenant_id' ORDER BY TABLE_NAME",String.class));
        menu=db.queryForObject("SELECT MIN(id) FROM admin_menu",Long.class);
        Map<String,Object> report=new LinkedHashMap<>();report.put("runtime","accepted final JAR / real MySQL 5.7");
        try{
            context=new AnnotationConfigApplicationContext(JpaOnly.class);factory=context.getBean(EntityManagerFactory.class);tx=new TransactionTemplate(context.getBean(JpaTransactionManager.class));
            SessionFactoryImplementor sf=factory.unwrap(SessionFactoryImplementor.class);
            Map<?,?> manifest=mapper.readValue(Paths.get(args[1]).toFile(),Map.class);Set<?> privateTables=new HashSet<>((List<?>)manifest.get("private"));List<Map<String,Object>> mappings=new ArrayList<>();
            for(javax.persistence.metamodel.EntityType<?> e:factory.getMetamodel().getEntities()){
                String table=e.getJavaType().getAnnotation(Table.class).name();boolean owned=TenantOwnedEntity.class.isAssignableFrom(e.getJavaType());
                check(owned==privateTables.contains(table),"Manifest ownership "+table);
                if(owned){org.hibernate.persister.entity.EntityPersister p=sf.getMetamodel().entityPersister(e.getJavaType());check(p.getClass()==TenantEntityPersister.class,"Runtime persister "+table);Map<String,Object> row=new LinkedHashMap<>();row.put("entity",e.getName());row.put("table",table);row.put("versioned",p.isVersioned());mappings.add(row);}
            }
            report.put("privateMappings",mappings);checks();report.put("status","PASS");
        }catch(Throwable e){report.put("status","FAIL");report.put("failureType",e.getClass().getName());throw e;}
        finally{report.put("checks",results);report.put("assertions",assertions);report.put("settingsAndPolicyFixtures",true);report.put("notFullHttpBusinessRetest",true);mapper.writerWithDefaultPrettyPrinter().writeValue(Paths.get(args[2]).toFile(),report);if(context!=null)context.close();TenantContext.clear();SecurityContextHolder.clearContext();}
    }
}
