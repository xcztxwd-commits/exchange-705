package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.AdminMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdminMenuRepository extends JpaRepository<AdminMenu, Long> {
    List<AdminMenu> findByStatusOrderBySortOrderAsc(String status);
    List<AdminMenu> findByParentIdAndStatusOrderBySortOrderAsc(Long parentId, String status);
    java.util.Optional<AdminMenu> findByMenuCode(String menuCode);
}

