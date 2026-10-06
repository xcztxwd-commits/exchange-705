package com.gtcfesk.exchange.control;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import javax.persistence.LockModeType;
import java.util.Optional;

/** Declared global CONTROL catalog; no tenant-owned business rows. */
public interface ControlPolicyDefinitionRepository extends JpaRepository<ControlPolicyDefinition,String> {
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select d from ControlPolicyDefinition d where d.key='feature.registration'")
 Optional<ControlPolicyDefinition> lockDefaults();
}
