package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.MenuAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MenuActionRepository extends JpaRepository<MenuAction, Long> {
    List<MenuAction> findByMenuIdOrderBySortOrderAsc(Long menuId);
    MenuAction findByMenuIdAndActionCode(Long menuId, String actionCode);
}




