package com.gtcfesk.exchange.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountInspectionTest {
    AccountInspection data;
    JdbcTemplate jdbc;
    @BeforeEach void setup(){
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        data=new AccountInspection(jdbc);
        jdbc.execute("create table asset_account(id bigint,user_id bigint,coin varchar(32),available decimal(32,16),frozen decimal(32,16),updated_at timestamp)");
        jdbc.update("insert into asset_account values (1,101,'FUND',123.45,2,CURRENT_TIMESTAMP),(2,102,'FUND',900,0,CURRENT_TIMESTAMP)");
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void userFilterAndPagination(){
        Map<String,Object> result=data.read("wallets",101L,null,1,20);
        assertEquals(1L,result.get("total"));assertEquals(1,((List<?>)result.get("rows")).size());
        assertEquals(2L,data.read("wallets",null,null,2,1).get("total"));
        assertEquals(0,((List<?>)data.read("wallets",999L,null,1,20).get("rows")).size());
    }
    @Test void rejectsSqlAndUnboundedRequests(){
        assertThrows(IllegalArgumentException.class,()->data.read("asset_account;drop table asset_account",null,null,1,20));
        assertThrows(IllegalArgumentException.class,()->data.read("wallets",null,null,0,20));
        assertThrows(IllegalArgumentException.class,()->data.read("wallets",null,null,1,101));
        assertThrows(IllegalArgumentException.class,()->data.read("wallets",null,"OPEN",1,20));
        for(String[] spec:AccountInspection.TYPES.values()) assertFalse(spec[2].matches(".*(password|token|signature|id_number|address).*"));
    }
    @Test void allCategoryProjectionsAreReadOnlyAndBounded(){
        for(Map.Entry<String,String[]> e:AccountInspection.TYPES.entrySet()) {
            if(e.getKey().equals("wallets"))continue;
            String[] spec=e.getValue();
            jdbc.execute("create table "+spec[0]+" ("+String.join(",",Arrays.stream(spec[2].split(",")).map(c->c+" varchar(128)").toArray(String[]::new))+")");
            assertEquals(0L,data.read(e.getKey(),null,null,1,20).get("total"));
        }
    }
    SimulationInspectionBoundary boundary(boolean demo){
        SimulationEnvironment env=mock(SimulationEnvironment.class);when(env.enabled()).thenReturn(demo);
        SimulationInspectionBoundary filter=new SimulationInspectionBoundary(env,data,new ObjectMapper());
        ReflectionTestUtils.setField(filter,"key","12345678901234567890123456789012");return filter;
    }
    MockHttpServletResponse call(SimulationInspectionBoundary filter,String method,String key) throws Exception {
        MockHttpServletRequest req=new MockHttpServletRequest(method,"/api/simulation/inspection");
        req.addParameter("kind","wallets");req.addParameter("page","1");req.addParameter("size","20");
        if(key!=null)req.addHeader("X-Simulation-Inspection-Key",key);
        MockHttpServletResponse res=new MockHttpServletResponse();filter.doFilter(req,res,new MockFilterChain());return res;
    }
    @Test void internalEndpointRequiresSecretAndDemo() throws Exception {
        assertEquals(403,call(boundary(true),"GET",null).getStatus());
        assertEquals(403,call(boundary(true),"GET","wrong").getStatus());
        assertEquals(403,call(boundary(false),"GET","12345678901234567890123456789012").getStatus());
    }
    @Test void internalEndpointNeverWrites() throws Exception {
        assertEquals(405,call(boundary(true),"POST","12345678901234567890123456789012").getStatus());
        MockHttpServletResponse res=call(boundary(true),"GET","12345678901234567890123456789012");
        assertEquals(200,res.getStatus());assertTrue(res.getContentAsString().contains("DEMO"));assertEquals("no-store",res.getHeader("Cache-Control"));
    }
    @Test void controllerChecksRolesAndModulePermissionsAndNeverFallsBack(){
        SimulationEnvironment env=mock(SimulationEnvironment.class);
        AdminPermissionService permissions=mock(AdminPermissionService.class);
        AdminAccountInspectionController c=new AdminAccountInspectionController(data,permissions,env);
        ReflectionTestUtils.setField(c,"key","");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1",null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_AGENT"))));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->c.read("REAL","wallets",null,null,1,20));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1",null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertEquals("REAL",c.read("REAL","wallets",101L,null,1,20).get("environment"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->c.read("DEMO","wallets",null,null,1,20));
        doThrow(new org.springframework.security.access.AccessDeniedException("denied")).when(permissions).require("orders","view");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->c.read("REAL","contracts",null,null,1,20));
    }
    @Test void demoQueryUsesSeparateServerAndRejectsWrongEnvironment() throws Exception {
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicReference<String> response=new java.util.concurrent.atomic.AtomicReference<>("{\"environment\":\"DEMO\",\"rows\":[{\"available\":77}],\"total\":1}");
        java.util.concurrent.atomic.AtomicReference<String> received=new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/inspection",exchange->{
            received.set(exchange.getRequestHeaders().getFirst("X-Simulation-Inspection-Key"));
            byte[] body=response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            AdminAccountInspectionController c=new AdminAccountInspectionController(data,mock(AdminPermissionService.class),mock(SimulationEnvironment.class));
            ReflectionTestUtils.setField(c,"key","12345678901234567890123456789012");
            ReflectionTestUtils.setField(c,"url","http://127.0.0.1:"+server.getAddress().getPort()+"/inspection");
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1",null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            Map<String,Object> result=c.read("DEMO","wallets",101L,null,1,20);
            assertEquals("DEMO",result.get("environment"));assertEquals(77,((Map<?,?>)((List<?>)result.get("rows")).get(0)).get("available"));
            assertEquals("12345678901234567890123456789012",received.get());
            response.set("{\"environment\":\"REAL\"}");
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->c.read("DEMO","wallets",101L,null,1,20));
        }finally{server.stop(0);}
    }
}
