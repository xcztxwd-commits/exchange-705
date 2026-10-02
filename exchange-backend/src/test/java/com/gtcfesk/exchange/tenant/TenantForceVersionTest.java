package com.gtcfesk.exchange.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.AssetAccount;
import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import org.hibernate.*;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;

/** Same assertions run on H2 by default and an explicitly provisioned disposable MySQL DB. */
class TenantForceVersionTest {
    static SessionFactory factory;
    static final List<String> writes = new ArrayList<>();
    Long id;
    public static class Observer implements org.hibernate.resource.jdbc.spi.StatementInspector {
        @Override public String inspect(String sql) {
            if (sql.toLowerCase(Locale.ROOT).startsWith("update asset_account ")) writes.add(sql);
            return sql;
        }
    }
    @BeforeAll static void start() throws Exception {
        Properties p = new Properties();
        String connection = System.getProperty("tenant.force.connection");
        if (connection == null) {
            p.setProperty("hibernate.connection.url", "jdbc:h2:mem:tenant_force;DB_CLOSE_DELAY=-1");
            p.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
            p.setProperty("hibernate.connection.username", "sa");
        } else {
            Map<?, ?> c = new ObjectMapper().readValue(new File(connection), Map.class);
            String url = (String)c.get("url");
            if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:64029/mt705_astra_force_[0-9_]+\\?.*"))
                throw new IllegalArgumentException("Requires dedicated disposable force-version database");
            p.setProperty("hibernate.connection.url", url);
            p.setProperty("hibernate.connection.username", (String)c.get("username"));
            p.setProperty("hibernate.connection.password", (String)c.get("password"));
            p.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQL57Dialect");
        }
        p.setProperty("hibernate.hbm2ddl.auto", "update");
        p.setProperty("hibernate.session_factory.statement_inspector", Observer.class.getName());
        factory = new Configuration().addAnnotatedClass(AssetAccount.class).addProperties(p).buildSessionFactory();
    }
    @AfterAll static void stop() { if (factory != null) factory.close(); }
    @BeforeEach void seed() {
        assertNull(TenantContext.currentTenantId());
        try (TenantContext.Scope ignored = TenantContext.open(1L); Session s = factory.openSession()) {
            s.beginTransaction();
            AssetAccount a = new AssetAccount(); a.setUserId(1L); a.setCoin(UUID.randomUUID().toString().replace("-", ""));
            id = (Long)s.save(a); s.getTransaction().commit();
        }
        writes.clear();
    }
    @AfterEach void cleanup() {
        assertNull(TenantContext.currentTenantId());
        try (TenantContext.Scope ignored = TenantContext.open(1L); Session s = factory.openSession()) {
            s.beginTransaction(); s.delete(s.get(AssetAccount.class, id)); s.getTransaction().commit();
        }
    }
    Object increment(long version) {
        try (Session s = factory.openSession()) {
            s.beginTransaction();
            try {
                Object next = ((SessionFactoryImplementor)factory).getMetamodel().entityPersister(AssetAccount.class)
                        .forceVersionIncrement(id, version, (SharedSessionContractImplementor)s);
                s.getTransaction().commit(); return next;
            } catch (RuntimeException e) { s.getTransaction().rollback(); throw e; }
        }
    }
    void state(long version, BigDecimal available) {
        try (TenantContext.Scope ignored = TenantContext.open(1L); Session s = factory.openSession()) {
            AssetAccount a = s.get(AssetAccount.class, id);
            assertEquals(version, a.getRowVersion()); assertEquals(0, available.compareTo(a.getAvailable()));
        }
    }
    void scopedSql() {
        assertFalse(writes.isEmpty());
        for (String sql : writes) {
            String where = sql.toLowerCase(Locale.ROOT).split(" where ")[1];
            assertTrue(where.contains("tenant_id="), sql);
            assertTrue(where.contains("row_version=?"), sql);
            assertTrue(where.contains("id=?"), sql);
        }
    }
    @Test void missingContextRejectsBeforeDml() {
        assertThrows(AccessDeniedException.class, () -> increment(0));
        assertTrue(writes.isEmpty()); state(0, BigDecimal.ZERO);
    }
    @Test void foreignTenantCannotIncrementKnownId() {
        try (TenantContext.Scope ignored = TenantContext.open(2L)) {
            assertThrows(StaleObjectStateException.class, () -> increment(0));
        }
        scopedSql(); state(0, BigDecimal.ZERO);
    }
    @Test void ownerIncrementAndStaleVersion() {
        try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            assertEquals(1L, increment(0));
            assertThrows(StaleObjectStateException.class, () -> increment(0));
        }
        scopedSql(); state(1, BigDecimal.ZERO);
    }
    @Test void pessimisticForceLockUsesScopedDml() { lock(LockMode.PESSIMISTIC_FORCE_INCREMENT); }
    @Test void optimisticForceLockUsesScopedDml() { lock(LockMode.OPTIMISTIC_FORCE_INCREMENT); }
    void lock(LockMode mode) {
        try (TenantContext.Scope ignored = TenantContext.open(1L); Session s = factory.openSession()) {
            s.beginTransaction(); AssetAccount a = s.get(AssetAccount.class, id);
            s.buildLockRequest(new LockOptions(mode)).lock(a); s.getTransaction().commit();
        }
        scopedSql(); state(1, BigDecimal.ZERO);
    }
    @Test void ordinaryDirtyUpdateStillScoped() {
        try (TenantContext.Scope ignored = TenantContext.open(1L); Session s = factory.openSession()) {
            s.beginTransaction(); s.get(AssetAccount.class, id).setAvailable(BigDecimal.TEN); s.getTransaction().commit();
        }
        scopedSql(); state(1, BigDecimal.TEN);
    }
    @Test void forcedIncrementRollsBack() {
        try (TenantContext.Scope ignored = TenantContext.open(1L); Session s = factory.openSession()) {
            s.beginTransaction();
            s.buildLockRequest(new LockOptions(LockMode.PESSIMISTIC_FORCE_INCREMENT)).lock(s.get(AssetAccount.class, id));
            s.getTransaction().rollback();
        }
        scopedSql(); state(0, BigDecimal.ZERO);
    }
}
