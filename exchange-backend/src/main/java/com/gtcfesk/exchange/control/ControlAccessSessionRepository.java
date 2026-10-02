package com.gtcfesk.exchange.control;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ControlAccessSessionRepository extends JpaRepository<ControlAccessSession,String> {
 @Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE) @Query("select s from ControlAccessSession s where s.ticketHash=:hash") java.util.Optional<ControlAccessSession> lockTicket(@Param("hash") String hash);
 @Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE) @Query("select s from ControlAccessSession s where s.id=:id") java.util.Optional<ControlAccessSession> lock(@Param("id") String id);
 org.springframework.data.domain.Page<ControlAccessSession> findByActorIdOrderByCreatedAtDesc(Long actorId,org.springframework.data.domain.Pageable pageable);
}
