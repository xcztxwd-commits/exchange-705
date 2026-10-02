package com.gtcfesk.exchange.config;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.enhanced.TableGenerator;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.Type;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;

/** Persistent, globally unique user IDs; existing users keep their IDs. */
public class UserIdGenerator extends TableGenerator {
    @Override
    public void configure(Type type, Properties parameters, ServiceRegistry services) {
        parameters.setProperty(TABLE_PARAM, "user_id_sequence");
        parameters.setProperty(SEGMENT_VALUE_PARAM, "user_account");
        parameters.setProperty(INITIAL_PARAM, "752911");
        parameters.setProperty(INCREMENT_PARAM, "1");
        super.configure(type, parameters, services);
    }

    @Override
    public Serializable generate(SharedSessionContractImplementor session, Object object) {
        // ponytail: bounded legacy collision scan; preflight must advance the sequence during a stopped-write migration if this budget is exhausted.
        for (int attempt = 0; attempt < 1024; attempt++) {
            Serializable candidate = super.generate(session, object);
            // Primary keys are global. Check occupancy across tenants without exposing user data.
            boolean occupied = session.doReturningWork(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM user_account WHERE id=?")) {
                    statement.setLong(1, ((Number) candidate).longValue());
                    try (ResultSet result = statement.executeQuery()) {
                        return result.next();
                    }
                }
            });
            if (!occupied) return candidate;
        }
        throw new org.hibernate.HibernateException("User ID collision budget exhausted; registration denied until an audited stopped-write sequence migration");
    }
}

