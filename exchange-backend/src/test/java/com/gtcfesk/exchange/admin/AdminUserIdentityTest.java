package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class AdminUserIdentityTest {
    @Test void batchesCurrentTenantIdentityWithoutConfusingOrderRemarks() {
        UserAccountRepository users=mock(UserAccountRepository.class);
        UserAccount user=new UserAccount();user.setId(101L);user.setEmail("user@example.com");user.setRemark("用户备注");user.setPasswordHash("not-for-admin-lists");
        when(users.findAllByTenantIdAndIdIn(eq(1L),any())).thenReturn(Collections.singletonList(user));
        Map<String,Object> first=new LinkedHashMap<>();first.put("userId",101L);first.put("remark","订单备注");
        Map<String,Object> second=new LinkedHashMap<>();second.put("userId",102L);second.put("remark","审核备注");
        Map<String,Object> unbound=new LinkedHashMap<>();unbound.put("userId",null);
        List<Map<String,Object>> rows=new AdminUserIdentity(users,new ObjectMapper()).rows(Arrays.asList(first,first,second,unbound));
        assertEquals("user@example.com",rows.get(0).get("userEmail"));assertEquals("用户备注",rows.get(0).get("userRemark"));
        assertEquals("订单备注",rows.get(0).get("remark"));assertFalse(rows.toString().contains("not-for-admin-lists"));
        assertNull(rows.get(2).get("userEmail"));assertNull(rows.get(2).get("userRemark"));assertNull(rows.get(3).get("userRemark"));
        assertEquals("审核备注",rows.get(2).get("remark"));verify(users,times(1)).findAllByTenantIdAndIdIn(eq(1L),eq(new HashSet<>(Arrays.asList(101L,102L))));
    }

    @Test void blankPagesAvoidQueriesAndEmailWildcardsStayLiteral() {
        UserAccountRepository users=mock(UserAccountRepository.class);
        assertTrue(new AdminUserIdentity(users,new ObjectMapper()).rows(Collections.emptyList()).isEmpty());verifyNoInteractions(users);
        assertNull(AdminUserIdentity.emailPattern("  "));
        assertEquals("%a!_!%!!@example.com%",AdminUserIdentity.emailPattern(" A_%!@Example.com "));
        assertThrows(IllegalArgumentException.class,()->AdminUserIdentity.emailPattern(String.join("",Collections.nCopies(255,"x"))));
    }
}
