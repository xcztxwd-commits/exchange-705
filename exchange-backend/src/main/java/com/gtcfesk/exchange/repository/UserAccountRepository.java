package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.UserAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByEmail(String email);
    Optional<UserAccount> findByPhone(String phone);
    Optional<UserAccount> findByMyInviteCode(String myInviteCode);
    boolean existsByEmail(String email);
    List<UserAccount> findByParentUserId(Long parentUserId);
    
    @Query("SELECT u FROM UserAccount u WHERE " +
           "(:keyword IS NULL OR :keyword = '' OR " +
           "u.email LIKE %:keyword% OR u.phone LIKE %:keyword% OR u.nickname LIKE %:keyword%) " +
           "AND (:status IS NULL OR :status = '' OR u.status = :status) " +
           "AND (:userType IS NULL OR :userType = '' OR u.userType = :userType)")
    Page<UserAccount> searchUsers(@Param("keyword") String keyword, 
                                   @Param("status") String status,
                                   @Param("userType") String userType,
                                   Pageable pageable);
}

