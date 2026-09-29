package com.gtcfesk.exchange.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.AdminPermissionService;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import javax.persistence.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InboxRecipientSearchTest {
    @Test void searchUsesSendPermissionProjectionAndBoundedLiteralQuery() {
        EntityManager em = mock(EntityManager.class);
        @SuppressWarnings("unchecked") TypedQuery<Object[]> query = mock(TypedQuery.class, RETURNS_SELF);
        SupportSettings settings = mock(SupportSettings.class);
        SupportSettings.Settings config = new SupportSettings.Settings(); config.inboxEnabled = true;
        when(settings.get()).thenReturn(config);
        AdminPermissionService permissions = mock(AdminPermissionService.class);
        SupportService service = new SupportService(settings, permissions, new ObjectMapper());
        ReflectionTestUtils.setField(service, "em", em);
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.singletonList(new Object[]{7L, "a@example.com"}));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1", null,
            Collections.singleton(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            assertEquals(7L, service.searchRecipients(" 7 ").get(0).get("id"));
            verify(permissions).require("inbox", "send");
            verify(query).setParameter("id", 7L);
            verify(query).setMaxResults(20);
            verify(em).createQuery(contains("(u.id=:id or"), eq(Object[].class));
            assertEquals(new HashSet<>(Arrays.asList("id", "email")), service.searchRecipients("A_%!").get(0).keySet());
            verify(query).setParameter("email", "%a!_!%!!%");
            assertTrue(service.searchRecipients("  ").isEmpty());
            assertThrows(ResponseStatusException.class, () -> service.searchRecipients(String.join("", Collections.nCopies(129, "a"))));
            service.searchRecipients("99999999999999999999999");
            config.inboxEnabled = false;
            assertThrows(ResponseStatusException.class, () -> service.searchRecipients("7"));
            config.inboxEnabled = true;
            doThrow(new AccessDeniedException("denied")).when(permissions).require("inbox", "send");
            assertThrows(AccessDeniedException.class, () -> service.searchRecipients("7"));
        } finally { SecurityContextHolder.clearContext(); }
    }
}
