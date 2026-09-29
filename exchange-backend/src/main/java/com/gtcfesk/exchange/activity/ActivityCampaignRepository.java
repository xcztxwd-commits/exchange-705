package com.gtcfesk.exchange.activity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import javax.persistence.LockModeType;
import java.util.Optional;
public interface ActivityCampaignRepository extends JpaRepository<ActivityCampaign,Long>{
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from ActivityCampaign c where c.id=:id") Optional<ActivityCampaign> lock(@Param("id") Long id);
}
