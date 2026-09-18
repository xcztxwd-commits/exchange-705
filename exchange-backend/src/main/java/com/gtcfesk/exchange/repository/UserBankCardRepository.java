package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.UserBankCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserBankCardRepository extends JpaRepository<UserBankCard, Long> {
    List<UserBankCard> findByUserId(Long userId);
}



