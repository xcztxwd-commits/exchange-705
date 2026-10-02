package com.gtcfesk.exchange.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.sql.*;
import java.util.*;
import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.entity.AssetAccount;

/** Standalone MySQL schema/repository probe. No application services, jobs or feeds start. */
public final class JpaTenantProbe {
    private static DataSource source;
    private static int checks;
    private static final List<String> writeShapes = new ArrayList<>();
    public static class SqlShapeObserver implements org.hibernate.resource.jdbc.spi.StatementInspector {
        @Override public String inspect(String sql) {
            String lower = sql.toLowerCase(Locale.ROOT);
            if (lower.startsWith("update asset_account ") || lower.startsWith("delete from asset_account ")) {
                String where = lower.substring(lower.indexOf(" where "));
                writeShapes.add((lower.startsWith("update") ? "UPDATE" : "DELETE") + " asset_account" + where);
            }
            return sql; // Observation only. Never repair/rewrite ORM SQL.
        }
    }
    @Configuration
    @EnableJpaRepositories(basePackages="com.gtcfesk.exchange", repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class)
    public static class ConfigurationOnly {
        @Bean public DataSource dataSource() { return source; }
        @Bean public LocalContainerEntityManagerFactoryBean entityManagerFactory() {
            LocalContainerEntityManagerFactoryBean bean=new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(source);bean.setPackagesToScan("com.gtcfesk.exchange");
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties properties=new Properties();
            properties.setProperty("hibernate.hbm2ddl.auto","validate");
            properties.setProperty("hibernate.session_factory.statement_inspector",SqlShapeObserver.class.getName());
            if(Boolean.getBoolean("probe.metadataOnly")) {
                properties.setProperty("hibernate.hbm2ddl.auto","none");
                properties.setProperty("javax.persistence.schema-generation.database.action","none");
                properties.setProperty("javax.persistence.schema-generation.scripts.action","create");
                properties.setProperty("javax.persistence.schema-generation.scripts.create-target","reports/multitenant/jpa-expected-schema.sql");
            }
            properties.setProperty("hibernate.dialect","org.hibernate.dialect.MySQL57Dialect");
            properties.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
            bean.setJpaProperties(properties);return bean;
        }
        @Bean public JpaTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    }
    private static void check(boolean result,String description) {
        if(!result) throw new AssertionError(description);checks++;
    }
    private static void failure(Connection connection,String sql,int expected,String description) throws SQLException {
        try(Statement statement=connection.createStatement()) {
            try { statement.executeUpdate(sql); } catch(SQLException error) {
                if(error.getErrorCode()!=expected) throw new AssertionError(description+": error "+error.getErrorCode());
                checks++;return;
            }
        }
        throw new AssertionError(description+": write unexpectedly accepted");
    }
    private static void supportConstraints() throws SQLException {
        try(Connection connection=source.getConnection();Statement statement=connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate("INSERT INTO control_admin(id,account,password_hash,enabled,mfa_enabled,session_version,row_version) VALUES(990001,'control_probe','NOT_A_PASSWORD',1,0,0,0)");
                check(statement.executeUpdate("UPDATE support_conversation SET control_actor_id=990001,admin_id=NULL WHERE tenant_id=1 AND id=1")==1,"Control support ownership without hidden tenant admin");
                statement.executeUpdate("INSERT INTO inbox_letter(tenant_id,user_id,control_actor_id,admin_id,request_id,title,content,created_at) VALUES(1,7000001,990001,NULL,'control-probe','Probe','Evidence',UTC_TIMESTAMP())");
                checks++;
                failure(connection,"INSERT INTO inbox_letter(tenant_id,user_id,control_actor_id,admin_id,request_id,title,content,created_at) VALUES(1,7000001,990001,NULL,'control-probe','Probe','Evidence',UTC_TIMESTAMP())",1062,"Control inbox retry unique");
                failure(connection,"INSERT INTO inbox_letter(tenant_id,user_id,control_actor_id,request_id,title,content,created_at) VALUES(2,7000001,990001,'foreign-user','Probe','Evidence',UTC_TIMESTAMP())",1452,"Control inbox must retain tenant user FK");
                failure(connection,"INSERT INTO inbox_letter(tenant_id,user_id,control_actor_id,request_id,title,content,created_at) VALUES(1,7000001,990002,'missing-control','Probe','Evidence',UTC_TIMESTAMP())",1452,"Control inbox true actor FK");
                failure(connection,"UPDATE support_conversation SET control_actor_id=990002 WHERE tenant_id=1 AND id=1",1452,"Control conversation true actor FK");
            } finally { connection.rollback(); }
        }
    }
    private static void denied(Runnable command,String description) {
        try { command.run(); } catch(RuntimeException expected) {checks++;return;}
        throw new AssertionError(description);
    }
    private static void repositoryIsolation(AnnotationConfigApplicationContext context) {
        UserAccountRepository users=context.getBean(UserAccountRepository.class);
        AssetAccountRepository assets=context.getBean(AssetAccountRepository.class);
        TransactionTemplate transaction=new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        denied(()->users.countByTenantId(2L),"Repository missing tenant must fail");
        try(TenantContext.Scope ignored=TenantContext.open(2L)) {
            transaction.execute(status->{
                status.setRollbackOnly();
                check(users.findByTenantIdAndId(2L,7000010L).isPresent(),"A owns synthetic user");
                check(!users.findByTenantIdAndId(2L,7000011L).isPresent(),"A cannot load B by ID");
                check(users.findAllByTenantId(2L,PageRequest.of(0,100)).stream().allMatch(u->u.getTenantId()==2L),"Pagination scope");
                check(users.findAllByTenantId(2L,(root,query,builder)->builder.conjunction()).stream().allMatch(u->u.getTenantId()==2L),"Specification scope");
                check(!users.lockById(7000011L).isPresent(),"Pessimistic foreign lock returns empty");
                check(users.findDepositCustomers(null,"%","%",PageRequest.of(0,100)).stream().allMatch(u->u.getTenantId()==2L),"JPQL OR search scope");
                check(users.touchActive(7000011L,java.time.LocalDateTime.now(),java.time.LocalDateTime.now().plusDays(1))==0,"Bulk update foreign user affects zero");
                AssetAccount created=new AssetAccount();created.setUserId(7000010L);created.setCoin("JPA_PROBE");
                assets.saveAndFlush(created);
                check(created.getTenantId()==2L && created.getId()!=null,"Save assigns tenant and database ID");
                created.setAvailable(java.math.BigDecimal.ONE);assets.flush();
                assets.deleteByTenantIdAndId(2L,created.getId());assets.flush();
                check(!assets.existsByTenantIdAndId(2L,created.getId()),"Owner-scoped lookup and physical delete");
                return null;
            });
            denied(()->users.findAllByTenantId(3L),"Explicit tenant argument must match context");
        }
        check(TenantContext.currentTenantId()==null,"JPA tenant context restored");
    }
    private static void physicalOwnershipChange(AnnotationConfigApplicationContext context) {
        AssetAccountRepository assets=context.getBean(AssetAccountRepository.class);
        TransactionTemplate transaction=new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        EntityManagerFactory factory=context.getBean(EntityManagerFactory.class);
        final Long foreignId;
        try(TenantContext.Scope ignored=TenantContext.open(3L)) {
            foreignId=transaction.execute(status->{AssetAccount asset=new AssetAccount();asset.setUserId(7000011L);asset.setCoin("SQL_FORGED_OWNER");return assets.saveAndFlush(asset).getId();});
        }
        try {
            for(boolean deleting:new boolean[]{false,true}) {
                try(TenantContext.Scope ignored=TenantContext.open(2L)) {
                    boolean stale=false;
                    int observedBefore=writeShapes.size();
                    try {
                        transaction.execute(status->{
                            // Hibernate's direct detached-entity API deliberately bypasses repository
                            // loading. Callbacks see A, while the actual row belongs to B.
                            AssetAccount forged=new AssetAccount();forged.setId(foreignId);forged.setTenantId(2L);
                            forged.setUserId(7000010L);forged.setCoin("SQL_FORGED_OWNER");forged.setRowVersion(0);
                            forged.setCreatedAt(java.time.LocalDateTime.now());forged.setUpdatedAt(java.time.LocalDateTime.now());
                            forged.setAvailable(java.math.BigDecimal.TEN);
                            javax.persistence.EntityManager em=EntityManagerFactoryUtils.getTransactionalEntityManager(factory);
                            org.hibernate.Session session=em.unwrap(org.hibernate.Session.class);
                            session.update(forged);
                            if(deleting) {session.setReadOnly(forged,true);session.delete(forged);}
                            em.flush();return null;
                        });
                    } catch(RuntimeException failure) {
                        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
                            if(cause instanceof org.hibernate.StaleStateException || cause instanceof javax.persistence.OptimisticLockException) stale=true;
                        if(!stale)throw failure;
                    }
                    check(writeShapes.subList(observedBefore,writeShapes.size()).stream().anyMatch(shape->shape.startsWith(deleting?"DELETE":"UPDATE") && shape.contains("tenant_id=2")), "The attempted DML itself reached the tenant-bound SQL");
                    check(stale,deleting?"Physical DELETE rejects a foreign ID even with forged in-memory tenant":"Physical UPDATE rejects a foreign ID even with forged in-memory tenant");
                }
            }
            try(TenantContext.Scope ignored=TenantContext.open(3L)) {
                check(assets.findByTenantIdAndId(3L,foreignId).get().getAvailable().signum()==0,"B row unchanged by both forged A writes");
            }
        } finally {
            try(TenantContext.Scope ignored=TenantContext.open(3L)) {
                transaction.execute(status->{assets.deleteByTenantIdAndId(3L,foreignId);assets.flush();return null;});
            }
        }
        try(TenantContext.Scope ignored=TenantContext.open(3L)) {
            transaction.execute(status->{
                status.setRollbackOnly();
                AssetAccount asset=new AssetAccount();asset.setUserId(7000011L);asset.setCoin("SQL_B_PROBE");assets.saveAndFlush(asset);
                asset.setAvailable(java.math.BigDecimal.TEN);assets.flush();
                assets.deleteByTenantIdAndId(3L,asset.getId());assets.flush();return null;
            });
        }
        check(writeShapes.stream().anyMatch(s->s.startsWith("UPDATE")&&s.contains("tenant_id=3")),"B UPDATE uses B's transaction tenant");
        check(writeShapes.stream().anyMatch(s->s.startsWith("DELETE")&&s.contains("tenant_id=3")),"B DELETE uses B's transaction tenant");
    }
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        Map<String,String> connection=new ObjectMapper().readValue(new File(args[0]),Map.class);
        source=DedicatedMysqlFixture.open(connection);
        supportConstraints();
        System.out.println("SUPPORT_CONTROL_MYSQL_PASS checks="+checks);
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext(ConfigurationOnly.class)) {
            int entityCount=context.getBean(EntityManagerFactory.class).getMetamodel().getEntities().size();
            check(entityCount>=50,"All application entity mappings scanned");
            org.hibernate.engine.spi.SessionFactoryImplementor factory=context.getBean(EntityManagerFactory.class).unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class);
            int privateCount=0;
            java.nio.file.Path manifestPath=java.nio.file.Paths.get(Objects.requireNonNull(connection.get("tableManifest"),"Reviewed table manifest required"));
            check(DedicatedMysqlFixture.hash(manifestPath).equals(connection.get("tableManifestSha256")),"Exact table manifest hash");
            com.fasterxml.jackson.databind.JsonNode manifest=new ObjectMapper().readTree(java.nio.file.Files.readAllBytes(manifestPath));
            Set<String> declaredPrivate=new HashSet<>(),declaredAll=new HashSet<>(),mappedPrivate=new TreeSet<>();
            for(String group:Arrays.asList("private","shared","control"))for(com.fasterxml.jackson.databind.JsonNode name:manifest.path(group)){declaredAll.add(name.asText());if("private".equals(group))declaredPrivate.add(name.asText());}
            for(javax.persistence.metamodel.EntityType<?> entity:context.getBean(EntityManagerFactory.class).getMetamodel().getEntities()) {
                org.hibernate.persister.entity.EntityPersister persister=factory.getMetamodel().entityPersister(entity.getJavaType());
                String table=((org.hibernate.persister.entity.AbstractEntityPersister)persister).getTableName().replace("`","").toLowerCase(Locale.ROOT);
                check(declaredAll.contains(table),"Every mapped entity table must be classified: "+table);
                boolean owned=TenantOwnedEntity.class.isAssignableFrom(entity.getJavaType());
                check(declaredPrivate.contains(table)==owned,"Manifest private mapping and tenant-owned entity must agree: "+table);
                if(owned) {
                    check(mappedPrivate.add(table),"Each private table is mapped once: "+table);
                    check(factory.getMetamodel().entityPersister(entity.getJavaType()).getClass()==TenantEntityPersister.class, "Private mapped entity must use tenant persister: "+entity.getName());
                    privateCount++;
                }
            }
            check(privateCount==mappedPrivate.size()&&!mappedPrivate.isEmpty(),"Every manifest-classified mapped private entity uses the physical write predicate");
            System.out.println("PRIVATE_MAPPED_ENTITIES count="+privateCount+" tables="+mappedPrivate);
            repositoryIsolation(context);
            physicalOwnershipChange(context);
            for (String shape : writeShapes) System.out.println("ORM_WRITE_SQL_SHAPE " + shape + " tenant_predicate=" + shape.contains("tenant_id"));
            check(writeShapes.stream().anyMatch(s -> s.startsWith("UPDATE") && s.contains("tenant_id=2") && s.contains("version=?")), "Dirty UPDATE retains tenant, ID and optimistic version");
            check(writeShapes.stream().anyMatch(s -> s.startsWith("DELETE") && s.contains("tenant_id=2") && s.contains("version=?")), "Managed DELETE retains tenant, ID and optimistic version");
            check(!writeShapes.isEmpty() && writeShapes.stream().allMatch(s -> s.contains("tenant_id=")), "Every observed ORM write has a tenant predicate");
            System.out.println((Boolean.getBoolean("probe.metadataOnly")?"JPA_TENANT_METADATA_ONLY_PASS":"JPA_TENANT_MYSQL_PASS")+" entities="+entityCount+" checks="+checks);
        }
    }
}
