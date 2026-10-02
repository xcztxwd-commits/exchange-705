package com.gtcfesk.exchange.config;

import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class UserIdGeneratorTest {
    private StandardServiceRegistry registry() {
        return new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:user_ids;MODE=MySQL;DB_CLOSE_DELAY=-1")
                .applySetting("hibernate.hbm2ddl.auto", "update")
                .applySetting("hibernate.connection.pool_size", "24")
                .build();
    }

    private SessionFactory factory(StandardServiceRegistry registry) {
        return new MetadataSources(registry).addAnnotatedClass(UserAccount.class).buildMetadata().buildSessionFactory();
    }

    private long register(SessionFactory factory, long tenant, String email, boolean commit) {
        try (TenantContext.Scope ignored = TenantContext.open(tenant); Session session = factory.openSession()) {
            session.beginTransaction();
            UserAccount user = new UserAccount();
            user.setEmail(email);
            user.setPasswordHash("test-only");
            session.persist(user);
            if (commit) session.getTransaction().commit();
            else session.getTransaction().rollback();
            return user.getId();
        }
    }

    @Test
    void startsAt752911PreservesExistingIdsAndSurvivesConcurrencyRollbackAndRestart() throws Exception {
        StandardServiceRegistry registry = registry();
        try (SessionFactory factory = factory(registry)) {
            try (Session session = factory.openSession()) {
                session.beginTransaction();
                session.createNativeQuery("INSERT INTO user_account(id,tenant_id,email,passwordHash,status,row_version,created_at,updated_at) "
                        + "VALUES (7000001,1,'old@test.invalid','test-only','normal',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                        + "(752912,2,'occupied@test.invalid','test-only','normal',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").executeUpdate();
                session.getTransaction().commit();
            }
            assertEquals(752911L, register(factory, 1L, "first@test.invalid", true));
            assertEquals(752913L, register(factory, 2L, "second@test.invalid", true));
            assertEquals(752914L, register(factory, 1L, "rollback@test.invalid", false));
            ExecutorService workers = Executors.newFixedThreadPool(8);
            try {
                List<Future<Long>> results = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    final int index = i;
                    results.add(workers.submit(() -> register(factory, index % 2 + 1L, "parallel" + index + "@test.invalid", true)));
                }
                HashSet<Long> ids = new HashSet<>();
                for (Future<Long> result : results) ids.add(result.get(30, TimeUnit.SECONDS));
                assertEquals(8, ids.size());
                assertEquals(752915L, ids.stream().mapToLong(Long::longValue).min().getAsLong());
                assertEquals(752922L, ids.stream().mapToLong(Long::longValue).max().getAsLong());
            } finally {
                workers.shutdownNow();
            }
            try (Session session = factory.openSession()) {
                assertEquals(1, ((Number) session.createNativeQuery("SELECT COUNT(*) FROM user_account WHERE id=7000001").uniqueResult()).intValue());
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
        registry = registry();
        try (SessionFactory factory = factory(registry)) {
            assertEquals(752923L, register(factory, 1L, "restart@test.invalid", true));
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
