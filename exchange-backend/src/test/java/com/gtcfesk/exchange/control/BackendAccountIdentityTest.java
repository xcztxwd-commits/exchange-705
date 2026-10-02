package com.gtcfesk.exchange.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.AdminUserIdentity;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class BackendAccountIdentityTest {
    @Test void agentIdentitySearchNeverTreatsAdminIdAsUserId() {
        BackendLoginRepository logins=mock(BackendLoginRepository.class);
        UserAccountRepository users=mock(UserAccountRepository.class);
        BackendLogin agent=new BackendLogin();agent.setSubjectType("AGENT");agent.setUserId(101L);
        BackendLogin admin=new BackendLogin();admin.setSubjectType("ADMIN");admin.setAdminUserId(101L);
        BackendLogin orphan=new BackendLogin();orphan.setSubjectType("AGENT");orphan.setUserId(102L);
        UserAccount user=new UserAccount();user.setId(101L);user.setEmail("Alpha_%!@example.com");user.setRemark("用户备注");user.setPasswordHash("must-not-leak");
        when(logins.findByTenantId(1L)).thenReturn(Arrays.asList(agent,admin,orphan));
        when(users.findAllByTenantIdAndIdIn(eq(1L),any())).thenReturn(Collections.singletonList(user));
        BackendAccountService service=new BackendAccountService(logins,null,null,null,null,new AdminUserIdentity(users,new ObjectMapper()));
        List<Map<String,Object>> rows=service.list(1L);
        assertEquals(3,rows.size());assertEquals(101L,rows.get(0).get("subjectId"));
        assertEquals("用户备注",rows.get(0).get("userRemark"));assertNull(rows.get(1).get("userEmail"));assertNull(rows.get(2).get("userRemark"));
        assertEquals(101L,rows.get(1).get("subjectId"));assertFalse(rows.toString().contains("must-not-leak"));
        assertEquals(1,service.list(1L," ALPHA_%! ").size());assertTrue(service.list(1L,"foreign@example.com").isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.list(1L,String.join("",Collections.nCopies(255,"x"))));
        verify(logins,times(3)).findByTenantId(1L);
    }
}
