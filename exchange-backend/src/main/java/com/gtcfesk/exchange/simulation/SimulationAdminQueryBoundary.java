package com.gtcfesk.exchange.simulation;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Internal read dispatch reuses original table query contracts, not duplicate SQL/DTOs. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+8) @RequiredArgsConstructor
public class SimulationAdminQueryBoundary extends OncePerRequestFilter {
    private static final Object READ_DISPATCH = new Object();
    public static boolean internalRead(HttpServletRequest request) {
        return request.getAttribute(SimulationAdminQueryBoundary.class.getName()) == READ_DISPATCH;
    }
    private final SimulationEnvironment environment;
    private final ObjectMapper mapper;
    @Value("${simulation.inspection-key:}") private String key;
    @Override protected boolean shouldNotFilter(HttpServletRequest r){return !"/api/simulation/admin-query".equals(r.getServletPath().isEmpty()?r.getRequestURI():r.getServletPath());}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        res.setHeader("Cache-Control","no-store");res.setHeader("X-Account-Environment","DEMO");
        String supplied=req.getHeader("X-Simulation-Inspection-Key");
        if(!environment.enabled() || key.length()<32 || supplied==null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))){res.setStatus(403);return;}
        if(!"POST".equals(req.getMethod())){res.setStatus(405);return;}
        AdminReadRoutes.Query q;
        try{q=mapper.readValue(req.getInputStream(),AdminReadRoutes.Query.class);AdminReadRoutes.permission(q.method,q.path);}catch(Exception e){res.setStatus(400);return;}
        byte[] bytes=mapper.writeValueAsBytes(q.body==null?Collections.emptyMap():q.body);
        Map<String,String[]> params=new LinkedHashMap<>();
        if(q.params!=null)q.params.forEach((k,v)->{if(v!=null)params.put(k,new String[]{String.valueOf(v)});});
        HttpServletRequestWrapper wrapped=new HttpServletRequestWrapper(req){
            @Override public String getMethod(){return q.method;}
            @Override public String getContentType(){return "application/json";}
            @Override public int getContentLength(){return bytes.length;}
            @Override public long getContentLengthLong(){return bytes.length;}
            @Override public String getHeader(String name){return null;}
            @Override public Enumeration<String> getHeaderNames(){return Collections.enumeration(Collections.<String>emptyList());}
            @Override public String getParameter(String name){return params.containsKey(name)?params.get(name)[0]:null;}
            @Override public String[] getParameterValues(String name){return params.get(name);}
            @Override public Map<String,String[]> getParameterMap(){return params;}
            @Override public Enumeration<String> getParameterNames(){return Collections.enumeration(params.keySet());}
            @Override public ServletInputStream getInputStream(){ByteArrayInputStream stream=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return stream.read();}public boolean isFinished(){return stream.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){}};}
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        };
        org.springframework.security.core.context.SecurityContext previous=SecurityContextHolder.getContext();
        org.springframework.security.core.context.SecurityContext context=SecurityContextHolder.createEmptyContext();
        // Only fixed read handlers can be reached; real service checked the human's live permissions.
        context.setAuthentication(new UsernamePasswordAuthenticationToken("-1",null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        SecurityContextHolder.setContext(context);
        req.setAttribute(SimulationAdminQueryBoundary.class.getName(),READ_DISPATCH);
        try{req.getRequestDispatcher(q.path).forward(wrapped,res);}finally{req.removeAttribute(SimulationAdminQueryBoundary.class.getName());SecurityContextHolder.setContext(previous);}
    }
}
