package com.gtcfesk.exchange.support;

import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.config.OperationLogInterceptor;
import com.gtcfesk.exchange.entity.OperationLog;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.util.ContentCachingRequestWrapper;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportAuditLogTest {
    private com.gtcfesk.exchange.tenant.TenantContext.Scope scope;
    @BeforeEach void tenant() { scope = com.gtcfesk.exchange.tenant.TenantContext.open(1L); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); if (scope != null) scope.close(); }
    @Test void ordinaryOperationLogsNeverExposeChatOrLetterBodies() throws Exception {
        OperationLogService logs = mock(OperationLogService.class);
        AdminUserRepository admins = mock(AdminUserRepository.class);
        AdminUser admin = new AdminUser(); admin.setId(1L); admin.setEmail("test@invalid");
        when(admins.findByTenantIdAndId(1L, 1L)).thenReturn(Optional.of(admin)); SupportServiceTest.auth(1L,"ADMIN");
        OperationLogInterceptor interceptor = new OperationLogInterceptor(logs,mock(JwtUtil.class),admins);
        MockHttpServletRequest raw = new MockHttpServletRequest("POST","/api/admin/support/sessions/1/messages");
        raw.setContentType("application/json"); raw.setContent("{\"text\":\"private customer content\"}".getBytes());
        ContentCachingRequestWrapper request = new ContentCachingRequestWrapper(raw);
        while(request.getInputStream().read() != -1) { }
        interceptor.afterCompletion(request,new MockHttpServletResponse(),null,null);
        ArgumentCaptor<OperationLog> capture = ArgumentCaptor.forClass(OperationLog.class);
        verify(logs).logOperation(capture.capture());
        assertFalse(capture.getValue().getRequestParams().contains("private customer content"));
        assertTrue(capture.getValue().getRequestParams().contains("privateCommunication"));
        reset(logs);
        interceptor.afterCompletion(new MockHttpServletRequest("GET","/api/admin/support/sessions/1/export"),new MockHttpServletResponse(),null,null);
        verify(logs).logOperation(any());
        reset(logs);
        interceptor.afterCompletion(new MockHttpServletRequest("POST","/api/admin/support/presence"),new MockHttpServletResponse(),null,null);
        verifyNoInteractions(logs);
    }
}
