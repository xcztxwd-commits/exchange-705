package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.UserAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends com.gtcfesk.exchange.tenant.TenantRepository<UserAccount, Long> {
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE UserAccount u SET u.lastActivityAt=:now WHERE u.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND u.id=:id AND (u.lastActivityAt IS NULL OR u.lastActivityAt<:before)")
    int touchActive(@Param("id") Long id, @Param("now") java.time.LocalDateTime now, @Param("before") java.time.LocalDateTime before);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE UserAccount u SET u.lastPageCode=:page, u.lastPageSeenAt=:now, u.lastDeviceType=:device, u.lastPageSequence=:sequence WHERE u.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND u.id=:id AND (u.lastPageSeenAt IS NULL OR u.lastPageSeenAt<:before OR u.lastPageCode IS NULL OR u.lastPageCode<>:page) AND (u.lastPageSequence IS NULL OR u.lastPageSequence<:sequence)")
    int reportPage(@Param("id") Long id, @Param("page") String page, @Param("device") String device,
            @Param("sequence") long sequence, @Param("now") java.time.LocalDateTime now, @Param("before") java.time.LocalDateTime before);
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE UserAccount u SET u.lastActivityAt = :now WHERE u.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (u.id = :id)")
    void touchActivity(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserAccount u WHERE u.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (u.id = :id)")
    Optional<UserAccount> lockById(@Param("id") Long id);

    @Query("SELECT u FROM UserAccount u WHERE u.tenantId=:tenantId AND LOWER(TRIM(u.email))=LOWER(TRIM(:email))")
    Optional<UserAccount> findByTenantIdAndEmail(@Param("tenantId") Long tenantId, @Param("email") String email);
    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserAccount u WHERE u.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND ((:agent IS NULL OR u.parentUserId = :agent) AND (str(u.id) LIKE :idPrefix ESCAPE '!' OR lower(u.email) LIKE :emailPattern ESCAPE '!')) ORDER BY u.id")
    Page<UserAccount> findDepositCustomers(@Param("agent") Long agent, @Param("idPrefix") String idPrefix,
                                          @Param("emailPattern") String emailPattern, Pageable pageable);
    Optional<UserAccount> findByTenantIdAndPhone(Long tenantId, String phone);
    Optional<UserAccount> findByTenantIdAndMyInviteCode(Long tenantId, String myInviteCode);
    @Query("SELECT CASE WHEN COUNT(u)>0 THEN true ELSE false END FROM UserAccount u WHERE u.tenantId=:tenantId AND LOWER(TRIM(u.email))=LOWER(TRIM(:email))")
    boolean existsByTenantIdAndEmail(@Param("tenantId") Long tenantId, @Param("email") String email);
    List<UserAccount> findByTenantIdAndParentUserId(Long tenantId, Long parentUserId);
    
    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserAccount u WHERE u.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND ((:keyword IS NULL OR :keyword = '' OR u.email LIKE %:keyword% OR u.phone LIKE %:keyword% OR u.nickname LIKE %:keyword%) AND (:status IS NULL OR :status = '' OR u.status = :status) AND (:userType IS NULL OR :userType = '' OR u.userType = :userType))")
    Page<UserAccount> searchUsers(@Param("keyword") String keyword, 
                                   @Param("status") String status,
                                   @Param("userType") String userType,
                                   Pageable pageable);
}

