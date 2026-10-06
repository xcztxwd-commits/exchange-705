package com.gtcfesk.exchange.config;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class ControlRequestLoggingTest {
    @Test void traceIsServerGeneratedAndRequestMetadataIsClearedAfterFailure() throws Exception {
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/admin/ai-control/61/restore");
        request.addHeader("X-Trace-Id","untrusted");MockHttpServletResponse response=new MockHttpServletResponse();
        IOException failure=assertThrows(IOException.class,()->new RequestLoggingFilter().doFilter(request,response,(r,s)->{
            assertNotEquals("untrusted",MDC.get("traceId"));assertEquals(response.getHeader("X-Trace-Id"),MDC.get("traceId"));
            MDC.put("requestKey","test-key");MDC.put("commandId","test-command");MDC.put("taskId","test-task");throw new IOException("Broken pipe");
        }));
        assertTrue(RequestLoggingFilter.clientDisconnected(failure));assertNull(MDC.get("traceId"));assertNull(MDC.get("requestKey"));assertNull(MDC.get("commandId"));assertNull(MDC.get("taskId"));
    }
    @Test void wrappedClientDisconnectDoesNotWriteAnotherErrorBody() {
        Exception failure=new IllegalStateException("response",new IOException("Broken pipe"));
        assertNull(new GlobalExceptionHandler().handleException(failure));
        assertFalse(RequestLoggingFilter.clientDisconnected(new IOException("upstream timeout")));
    }
    @Test void pessimisticTimeoutIsRecoverableButOptimisticConflictRemains409() {
        GlobalExceptionHandler handler=new GlobalExceptionHandler();
        org.springframework.http.ResponseEntity<java.util.Map<String,Object>> result=handler.handleConflict(new org.springframework.dao.CannotAcquireLockException("wait",new java.sql.SQLException("lock wait timeout","HY000",1205)));
        assertEquals(503,result.getStatusCodeValue());assertEquals("ENGINE_TRANSIENT",result.getBody().get("errorCode"));
        assertEquals("1",result.getHeaders().getFirst("Retry-After"));
        assertEquals(409,handler.handleConflict(new org.springframework.dao.OptimisticLockingFailureException("version")).getStatusCodeValue());
    }
    @Test void wrappedSqlFenceAndBudgetHaveRecoverableHttpClassification() {
        java.sql.SQLException fence=new java.sql.SQLException("ENGINE_FENCED","45000",1644);
        org.springframework.http.ResponseEntity<java.util.Map<String,Object>> result=new GlobalExceptionHandler().handleException(new IllegalStateException("jdbc",fence));
        assertEquals(503,result.getStatusCodeValue());assertEquals("ENGINE_FENCED",result.getBody().get("errorCode"));
        result=new GlobalExceptionHandler().handleBusinessException(new com.gtcfesk.exchange.common.BusinessException("ENGINE_BUDGET: expired"));
        assertEquals(503,result.getStatusCodeValue());assertEquals("ENGINE_BUDGET",result.getBody().get("errorCode"));
    }
}
