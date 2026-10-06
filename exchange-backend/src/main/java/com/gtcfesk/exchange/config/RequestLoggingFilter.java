package com.gtcfesk.exchange.config;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path=request.getRequestURI();
        boolean control=path.startsWith("/api/admin/ai-control"),image=path.contains("/uploads/images/");
        if(!control && !image){chain.doFilter(request,response);return;}
        String trace=UUID.randomUUID().toString();long at=System.nanoTime();
        // Trace IDs are generated here; no untrusted headers, query string or request body is logged.
        MDC.put("traceId",trace);response.setHeader("X-Trace-Id",trace);
        String outcome="RESPONSE";
        try {chain.doFilter(request,response);}
        catch(IOException failure){outcome=clientDisconnected(failure)?"CLIENT_DISCONNECTED":"IO_FAILURE";throw failure;}
        finally {
            LoggerFactory.getLogger(getClass()).info("control_http traceId={} requestKey={} commandId={} taskId={} tenant={} action={} method={} path={} httpStatus={} ms={} outcome={}",trace,MDC.get("requestKey"),MDC.get("commandId"),MDC.get("taskId"),com.gtcfesk.exchange.tenant.TenantContext.currentTenantId(),path.substring(path.lastIndexOf('/')+1),request.getMethod(),path,response.getStatus(),(System.nanoTime()-at)/1000000,outcome);
            MDC.remove("traceId");MDC.remove("requestKey");MDC.remove("commandId");MDC.remove("taskId");
        }
    }
    public static boolean clientDisconnected(Throwable failure) {
        for(Throwable t=failure;t!=null;t=t.getCause()) {
            String message=t.getMessage();
            if(t instanceof org.apache.catalina.connector.ClientAbortException || t instanceof IOException && message!=null && (message.contains("Broken pipe") || message.contains("Connection reset")))return true;
        }
        return false;
    }
}
