package com.gtcfesk.exchange.activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
import java.util.Optional;
public interface ActivityDeliveryRepository extends JpaRepository<ActivityDelivery,Long>,org.springframework.data.jpa.repository.JpaSpecificationExecutor<ActivityDelivery>{
 Optional<ActivityDelivery> findByCampaignIdAndUserId(Long campaignId,Long userId);
 Page<ActivityDelivery> findByUserIdOrderByIdDesc(Long userId,Pageable page);
 long countByCampaignId(Long id);
 long countByCampaignIdAndReceivedAtIsNotNull(Long id);
 long countByCampaignIdAndOpenedAtIsNotNull(Long id);
 long countByCampaignIdAndClosedAtIsNotNull(Long id);
 long countByCampaignIdAndClosedAtIsNotNullAndOpenedAtIsNull(Long id);
}
