package com.gtcfesk.exchange.control;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ControlAdminRepository extends JpaRepository<ControlAdmin,Long> {
 java.util.Optional<ControlAdmin> findByAccount(String account);
 @Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE) @Query("select a from ControlAdmin a where a.id=:id") java.util.Optional<ControlAdmin> lock(@Param("id") Long id);
}
