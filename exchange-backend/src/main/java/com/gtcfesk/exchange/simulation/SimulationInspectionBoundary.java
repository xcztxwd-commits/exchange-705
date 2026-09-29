package com.gtcfesk.exchange.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Server-to-server, read-only endpoint. No administrator session is copied into the demo DB. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 9) @RequiredArgsConstructor
public class SimulationInspectionBoundary extends OncePerRequestFilter {
    private final SimulationEnvironment environment;
    private final AccountInspection inspection;
    private final ObjectMapper mapper;
    @Value("${simulation.inspection-key:}") private String key;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/simulation/inspection".equals(request.getServletPath().isEmpty()?request.getRequestURI():request.getServletPath());
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException {
        res.setContentType("application/json;charset=UTF-8");res.setHeader("Cache-Control","no-store");
        String supplied=req.getHeader("X-Simulation-Inspection-Key");
        if(!environment.enabled() || key.length()<32 || supplied==null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {res.setStatus(403);return;}
        if(!"GET".equals(req.getMethod())){res.setStatus(405);return;}
        try {
            Map<String,Object> result=inspection.read(req.getParameter("kind"),req.getParameter("userId")==null?null:Long.valueOf(req.getParameter("userId")),req.getParameter("status"),Integer.parseInt(req.getParameter("page")),Integer.parseInt(req.getParameter("size")));
            result.put("environment","DEMO");mapper.writeValue(res.getWriter(),result);
        } catch(IllegalArgumentException e){res.setStatus(400);mapper.writeValue(res.getWriter(),java.util.Collections.singletonMap("message","筛选参数无效"));}
    }
}
