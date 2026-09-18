package com.gtcfesk.exchange.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {
    Optional<AdminUser> findByAccount(String account);
    Optional<AdminUser> findByEmail(String email);
}







