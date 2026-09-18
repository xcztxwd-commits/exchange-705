package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.UserMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserMenuRepository extends JpaRepository<UserMenu, Long> {
    List<UserMenu> findByUserId(Long userId);
    void deleteByUserId(Long userId);
    void deleteByUserIdAndMenuId(Long userId, Long menuId);
    boolean existsByUserIdAndMenuId(Long userId, Long menuId);
}

