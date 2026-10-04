package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantOwnedEntity;
import org.springframework.dao.CannotAcquireLockException;

import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import java.time.LocalDate;

/** Financial-only current reads before Hibernate upgrades an old managed @Version entity's lock. */
final class FinancialCurrentLocks {
    private FinancialCurrentLocks() {}
    static void user(EntityManager em, Long id) {
        refresh(em, UserAccount.class, "SELECT * FROM user_account WHERE tenant_id=?1 AND id=?2 FOR UPDATE", id);
    }
    static void assets(EntityManager em, Long owner) {
        refresh(em, AssetAccount.class, "SELECT * FROM asset_account WHERE tenant_id=?1 AND user_id=?2 ORDER BY coin,id FOR UPDATE", owner);
    }
    static void order(EntityManager em, Long id) {
        refresh(em, FinancialOrder.class, "SELECT * FROM financial_order WHERE tenant_id=?1 AND id=?2 FOR UPDATE", id);
    }
    static FinancialOrder request(EntityManager em, Long owner, String key) {
        em.flush();
        // This lookup may use an old RR snapshot. Missing means attempt INSERT, never lock the gap.
        java.util.List<?> ids=em.createNativeQuery("SELECT id FROM financial_order WHERE tenant_id=?1 AND user_id=?2 AND request_key=?3")
                .setParameter(1,TenantContext.requireTenantId()).setParameter(2,owner).setParameter(3,key).getResultList();
        return ids.isEmpty()?null:refresh(em,FinancialOrder.class,
                "SELECT * FROM financial_order WHERE tenant_id=?1 AND id=?2 FOR UPDATE",((Number)ids.get(0)).longValue()).get(0);
    }
    static FinancialOrder committedRequest(EntityManager em, Long owner, String key) {
        // Called only after the exact unique-key INSERT conflict proves a committed receipt exists.
        java.util.List<FinancialOrder> rows=refresh(em,FinancialOrder.class,
                "SELECT * FROM financial_order WHERE tenant_id=?1 AND user_id=?2 AND request_key=?3 FOR UPDATE",owner,key);
        if(rows.size()!=1) throw new IllegalStateException("Financial duplicate request has no unique current receipt");
        return rows.get(0);
    }
    static boolean insertRequest(EntityManager em, FinancialOrder order) {
        order.assignTenantBeforeInsert(); order.prePersist(); em.flush();
        // Native JDBC is required here: a caught Hibernate persist/flush duplicate poisons the whole
        // persistence context and prevents the legacy ambient transaction from returning its receipt.
        Long id=em.unwrap(org.hibernate.Session.class).doReturningWork(connection -> {
            String sql="INSERT INTO financial_order (tenant_id,row_version,user_id,product_id,product_name,purchase_amount,currency,daily_yield_rate,daily_yield,total_yield,last_accrued_date,accrued_yield,term_days,penalty_rate,penalty_amount,status,purchase_time,end_time,request_key,request_hash,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try(java.sql.PreparedStatement statement=connection.prepareStatement(sql,java.sql.Statement.RETURN_GENERATED_KEYS)) {
                Object[] values={order.getTenantId(),order.getRowVersion(),order.getUserId(),order.getProductId(),order.getProductName(),order.getPurchaseAmount(),order.getCurrency(),order.getDailyYieldRate(),order.getDailyYield(),order.getTotalYield(),java.sql.Date.valueOf(order.getLastAccruedDate()),order.getAccruedYield(),order.getTermDays(),order.getPenaltyRate(),order.getPenaltyAmount(),order.getStatus(),java.sql.Timestamp.valueOf(order.getPurchaseTime()),java.sql.Timestamp.valueOf(order.getEndTime()),order.getRequestKey(),order.getRequestHash(),java.sql.Timestamp.valueOf(order.getCreatedAt()),java.sql.Timestamp.valueOf(order.getUpdatedAt())};
                for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
                if(statement.executeUpdate()!=1) throw new java.sql.SQLException("Financial request insert did not insert exactly one row");
                try(java.sql.ResultSet keys=statement.getGeneratedKeys()) {
                    if(!keys.next() || keys.getLong(1)<=0) throw new java.sql.SQLException("Financial request insert has no generated id");
                    return keys.getLong(1);
                }
            } catch(java.sql.SQLException failure) {
                if(requestDuplicate(failure)) return null;
                throw failure;
            }
        });
        if(id==null) return false;
        order.setId(id); return true;
    }
    private static boolean requestDuplicate(java.sql.SQLException failure) {
        boolean mysql=failure.getErrorCode()==1062 && "23000".equals(failure.getSQLState());
        boolean h2="23505".equals(failure.getSQLState());
        String message=failure.getMessage();
        return (mysql || h2) && message!=null && java.util.regex.Pattern.compile(
                "(?i)\\buk_financial_order_request(?:_index_[a-z0-9]+)?(?:\\s|['\"`])").matcher(message).find();
    }
    static void yield(EntityManager em, Long id) {
        refresh(em, FinancialYieldRecord.class, "SELECT * FROM financial_yield_record WHERE tenant_id=?1 AND id=?2 FOR UPDATE", id);
    }
    static void dates(EntityManager em, Long order, LocalDate first, LocalDate last) {
        refresh(em, FinancialYieldRecord.class, "SELECT * FROM financial_yield_record WHERE tenant_id=?1 AND order_id=?2 AND yield_date BETWEEN ?3 AND ?4 ORDER BY id FOR UPDATE", order, first, last);
    }
    private static <T extends TenantOwnedEntity> java.util.List<T> refresh(EntityManager em, Class<T> type, String sql, Object... parameters) {
        if (em==null) return java.util.Collections.emptyList(); // Direct-construction calculation tests have no JPA persistence context.
        try {
            // Preserve the caller's earlier writes; never clear/detach the caller's transaction or discard dirty balances.
            em.flush();
            org.hibernate.query.NativeQuery<?> query=em.createNativeQuery(sql).unwrap(org.hibernate.query.NativeQuery.class);
            // NONE governs ORM hydration only: every SQL statement still acquires FOR UPDATE row locks.
            // Do not upgrade/check an old managed version until direct refresh has replaced its state.
            query.addEntity("locked",type,org.hibernate.LockMode.NONE);
            query.setParameter(1,TenantContext.requireTenantId());
            for (int i=0; i<parameters.length; i++) query.setParameter(i+2,parameters[i]);
            // Hydrate whole current rows, including accounts inserted while the user lock was awaited.
            // Refreshing getReference's uninitialized proxy alone returns early in Hibernate 5.6 and
            // its later ordinary initialization can read an old RR snapshot. Native hydration avoids it.
            java.util.List<T> rows=new java.util.ArrayList<>();
            for (Object row : query.getResultList()) {
                T entity=type.cast(row);
                em.refresh(entity,LockModeType.PESSIMISTIC_WRITE);
                TenantContext.require(entity.getTenantId());
                rows.add(entity);
            }
            return rows;
        } catch (javax.persistence.LockTimeoutException | javax.persistence.PessimisticLockException failure) {
            throw new CannotAcquireLockException("Financial current row lock failed",failure);
        }
    }
}
