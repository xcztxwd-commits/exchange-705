package com.gtcfesk.exchange.activity;
import com.gtcfesk.exchange.tenant.TenantRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
public interface ActivitySelectionMemberRepository extends TenantRepository<ActivitySelectionMember,Long> {
 List<ActivitySelectionMember> findByTenantIdAndSelectionIdAndIdGreaterThanOrderByIdAsc(Long tenant,Long selection,long cursor,Pageable page);
 org.springframework.data.domain.Page<ActivitySelectionMember> findByTenantIdAndSelectionIdOrderByIdAsc(Long tenant,Long selection,Pageable page);
 java.util.Optional<ActivitySelectionMember> findByTenantIdAndSelectionIdAndUserId(Long tenant,Long selection,Long user);
}
