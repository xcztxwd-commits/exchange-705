package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.AdminRoleMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface AdminRoleMenuRepository extends JpaRepository<AdminRoleMenu, Long> {
    List<AdminRoleMenu> findByRoleId(Long roleId);
    
    @Modifying
    @Transactional
    @Query("DELETE FROM AdminRoleMenu rm WHERE rm.roleId = ?1")
    void deleteByRoleId(Long roleId);
}

