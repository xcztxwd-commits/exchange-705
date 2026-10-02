package com.gtcfesk.exchange.tenant;

import java.io.Serializable;
import org.hibernate.HibernateException;
import org.hibernate.cache.spi.access.EntityDataAccess;
import org.hibernate.cache.spi.access.NaturalIdDataAccess;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.persister.entity.SingleTableEntityPersister;
import org.hibernate.persister.spi.PersisterCreationContext;

/** Private single-table entities only. Native/bulk queries retain explicit tenant predicates.
 * Complements scoped repository reads and lifecycle ownership checks: dirty flushes and
 * managed deletes must also constrain the physical SQL to the server's transaction tenant.
 * This Hibernate 5.6 extension is verified against captured SQL, not a global SQL rewriter.
 */
public final class TenantEntityPersister extends SingleTableEntityPersister {
    public TenantEntityPersister(PersistentClass mapping, EntityDataAccess entityCache,
            NaturalIdDataAccess naturalIdCache, PersisterCreationContext context) {
        super(mapping, entityCache, naturalIdCache, context);
        if (!TenantOwnedEntity.class.isAssignableFrom(mapping.getMappedClass())
                || mapping.getJoinClosureSpan() != 0 || mapping.getSuperclass() != null) {
            throw new HibernateException("Tenant persister requires a private single-table entity");
        }
    }

    private String scoped(String sql, Object entity, int tableIndex) {
        Long tenantId = TenantContext.requireTenantId();
        if (!(entity instanceof TenantOwnedEntity) || tableIndex != 0)
            throw new HibernateException("Tenant write requires a managed single-table owner");
        TenantContext.require(((TenantOwnedEntity) entity).getTenantId());
        if (sql == null || !sql.toLowerCase(java.util.Locale.ROOT).contains(" where ")
                || sql.indexOf(';') >= 0)
            throw new HibernateException("Unexpected private entity write SQL");
        // Positive Long comes exclusively from verified TenantContext. No caller SQL/values.
        return sql + " and tenant_id=" + tenantId;
    }


    /** Hibernate's FORCE lock bypasses update(); retain ID + version + verified tenant in this DML too. */
    @Override public Object forceVersionIncrement(Serializable id, Object version,
            SharedSessionContractImplementor session) {
        Long tenant = TenantContext.requireTenantId();
        if (!isVersioned() || isVersionPropertyGenerated())
            throw new HibernateException("Tenant force increment requires an explicit version property");
        Object next = getVersionType().next(version, session);
        String sql = new org.hibernate.sql.Update(getFactory().getJdbcServices().getDialect())
                .setTableName(getVersionedTableName()).addColumn(getVersionColumnName())
                .addPrimaryKeyColumns(getIdentifierColumnNames()).setVersionColumnName(getVersionColumnName())
                .toStatementString() + " and tenant_id=?";
        org.hibernate.engine.jdbc.spi.JdbcCoordinator jdbc = session.getJdbcCoordinator();
        java.sql.PreparedStatement statement = null;
        try {
            statement = jdbc.getStatementPreparer().prepareStatement(sql, false);
            getVersionType().nullSafeSet(statement, next, 1, session);
            getIdentifierType().nullSafeSet(statement, id, 2, session);
            int position = 2 + getIdentifierType().getColumnSpan(getFactory());
            getVersionType().nullSafeSet(statement, version, position, session);
            statement.setLong(position + 1, tenant);
            if (jdbc.getResultSetReturn().executeUpdate(statement) != 1)
                throw new org.hibernate.StaleObjectStateException(getEntityName(), id);
            return next;
        } catch (java.sql.SQLException e) {
            throw getFactory().getSQLExceptionHelper().convert(e, "Could not increment tenant entity version", sql);
        } finally {
            if (statement != null) jdbc.getLogicalConnection().getResourceRegistry().release(statement);
            jdbc.afterStatementExecution();
        }
    }

    @Override public boolean update(Serializable id, Object[] fields, Object[] oldFields,
            Object rowId, boolean[] includeProperty, int tableIndex, Object oldVersion,
            Object entity, String sql, SharedSessionContractImplementor session) {
        return super.update(id, fields, oldFields, rowId, includeProperty, tableIndex,
                oldVersion, entity, scoped(sql, entity, tableIndex), session);
    }

    @Override public void delete(Serializable id, Object version, int tableIndex,
            Object entity, String sql, SharedSessionContractImplementor session,
            Object[] loadedState) {
        super.delete(id, version, tableIndex, entity, scoped(sql, entity, tableIndex),
                session, loadedState);
    }
}
