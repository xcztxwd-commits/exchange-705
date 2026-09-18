package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.UserAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface UserActionRepository extends JpaRepository<UserAction, Long> {
    List<UserAction> findByUserId(Long userId);
    List<UserAction> findByUserIdAndMenuId(Long userId, Long menuId);
    boolean existsByUserIdAndMenuIdAndActionCode(Long userId, Long menuId, String actionCode);
    
    @Modifying
    @Transactional
    @Query("DELETE FROM UserAction ua WHERE ua.userId = ?1")
    void deleteByUserId(Long userId);
    
    @Modifying
    @Transactional
    @Query("DELETE FROM UserAction ua WHERE ua.userId = ?1 AND ua.menuId = ?2")
    void deleteByUserIdAndMenuId(Long userId, Long menuId);
}




