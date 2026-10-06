package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.AdminTablePreference;
import com.gtcfesk.exchange.repository.AdminTablePreferenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminTablePreferenceTest {
    @Test void httpRoundTripPreservesUtf8AndStableIds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AdminTablePreferenceRepository repo = mock(AdminTablePreferenceRepository.class);
        Map<String, AdminTablePreference> data = new HashMap<>();
        when(repo.findByTenantIdAndId(eq(1L), anyString())).thenAnswer(c -> Optional.ofNullable(data.get(c.getArgument(1))));
        when(repo.save(any())).thenAnswer(c -> { AdminTablePreference p = c.getArgument(0); data.put(p.getId(), p); return p; });
        MockMvc http = MockMvcBuilders.standaloneSetup(new AdminTablePreferenceController(repo, mapper)).build();
        try (com.gtcfesk.exchange.tenant.TenantContext.Scope ignored = com.gtcfesk.exchange.tenant.TenantContext.open(1L)) {
            login("1", "ROLE_ADMIN");
            for (String id : Arrays.asList("手机号", "年收入", "登录IP / 地区", "用户类型", "操作", "annualIncome")) {
                String body = "[" + mapper.createObjectNode().put("id", id).put("visible", true).put("fixed", "left").toString() + "]";
                for (String contentType : Arrays.asList("application/json", "application/json;charset=UTF-8")) {
                    http.perform(put("/api/admin/table-preferences/Users.1").contentType(contentType).content(body.getBytes(StandardCharsets.UTF_8)))
                            .andExpect(status().isOk()).andExpect(content().json("{\"success\":true}"));
                    String response = http.perform(get("/api/admin/table-preferences/Users.1"))
                            .andExpect(status().isOk()).andExpect(content().contentType("application/json;charset=UTF-8"))
                            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertEquals(mapper.readTree(body), mapper.readTree(response));
                    assertEquals(mapper.readTree(body), mapper.readTree(data.get("tenant:1:admin:1:Users.1").getColumnsJson()));
                }
            }
            doThrow(new IllegalStateException("database unavailable")).when(repo).save(any());
            assertThrows(IllegalStateException.class, () -> new AdminTablePreferenceController(repo, mapper)
                    .save("Users.1", mapper.readTree("[{\"id\":\"id\",\"visible\":true,\"fixed\":\"\"}]")));
        } finally { SecurityContextHolder.clearContext(); }
    }
    private void login(String id, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id, null,
                Collections.singletonList(new SimpleGrantedAuthority(role))));
    }
    @Test void controlAccessUsesStableActorAcrossSessionsAndDifferentTenant() throws Exception {
        ObjectMapper mapper=new ObjectMapper();AdminTablePreferenceRepository repo=mock(AdminTablePreferenceRepository.class);Map<String,AdminTablePreference> data=new HashMap<>();
        when(repo.findByTenantIdAndId(anyLong(),anyString())).thenAnswer(c->Optional.ofNullable(data.get(c.getArgument(0)+"/"+c.getArgument(1))));when(repo.save(any())).thenAnswer(c->{AdminTablePreference p=c.getArgument(0);data.put(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+"/"+p.getId(),p);return p;});
        AdminTablePreferenceController controller=new AdminTablePreferenceController(repo,mapper);com.fasterxml.jackson.databind.JsonNode config=mapper.readTree("[{\"id\":\"id\",\"visible\":true,\"fixed\":\"left\"}]");
        try {
            login("-11","ROLE_SUPER_ADMIN");((UsernamePasswordAuthenticationToken)SecurityContextHolder.getContext().getAuthentication()).setDetails(new com.gtcfesk.exchange.control.ControlIdentity(11L,1L,"session-a"));
            try(com.gtcfesk.exchange.tenant.TenantContext.Scope ignored=com.gtcfesk.exchange.tenant.TenantContext.open(1L)){controller.save("Users.1",config);}
            login("-999","ROLE_SUPER_ADMIN");((UsernamePasswordAuthenticationToken)SecurityContextHolder.getContext().getAuthentication()).setDetails(new com.gtcfesk.exchange.control.ControlIdentity(11L,1L,"session-b"));
            try(com.gtcfesk.exchange.tenant.TenantContext.Scope ignored=com.gtcfesk.exchange.tenant.TenantContext.open(1L)){assertEquals(config,controller.get("Users.1"));}
            ((UsernamePasswordAuthenticationToken)SecurityContextHolder.getContext().getAuthentication()).setDetails(new com.gtcfesk.exchange.control.ControlIdentity(11L,2L,"session-c"));
            try(com.gtcfesk.exchange.tenant.TenantContext.Scope ignored=com.gtcfesk.exchange.tenant.TenantContext.open(2L)){assertEquals(0,controller.get("Users.1").size());}
            login("11","ROLE_SUPER_ADMIN");try(com.gtcfesk.exchange.tenant.TenantContext.Scope ignored=com.gtcfesk.exchange.tenant.TenantContext.open(1L)){assertEquals(0,controller.get("Users.1").size());}
        }finally{SecurityContextHolder.clearContext();}
    }
    @Test void isolatesAccountsAndTablesAndValidatesColumns() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AdminTablePreferenceRepository repository = mock(AdminTablePreferenceRepository.class);
        Map<String, AdminTablePreference> database = new HashMap<>();
        when(repository.findByTenantIdAndId(org.mockito.ArgumentMatchers.eq(1L), anyString())).thenAnswer(call -> Optional.ofNullable(database.get(call.getArgument(1))));
        when(repository.save(any())).thenAnswer(call -> { AdminTablePreference value = call.getArgument(0); database.put(value.getId(), value); return value; });
        AdminTablePreferenceController controller = new AdminTablePreferenceController(repository, mapper);
        try (com.gtcfesk.exchange.tenant.TenantContext.Scope ignored = com.gtcfesk.exchange.tenant.TenantContext.open(1L)) {
            login("1", "ROLE_ADMIN");
            com.fasterxml.jackson.databind.JsonNode config = mapper.readTree("[{\"id\":\"email\",\"visible\":true,\"fixed\":\"left\"},{\"id\":\"id\",\"visible\":false,\"fixed\":\"\"}]");
            controller.save("Users.1", config);
            assertEquals(config, controller.get("Users.1"));
            assertEquals(0, controller.get("Orders.1").size());
            login("2", "ROLE_ADMIN");
            assertEquals(0, controller.get("Users.1").size());
            login("agent-1", "ROLE_AGENT");
            assertEquals(0, controller.get("Users.1").size());
            controller.save("Users.1", mapper.createArrayNode());
            login("1", "ROLE_SUPER_ADMIN");
            assertEquals(config, controller.get("Users.1"));
            assertThrows(ResponseStatusException.class, () -> controller.save("Users.1", mapper.readTree("[{\"id\":\"x\",\"visible\":false,\"fixed\":\"\"}]")));
            assertThrows(ResponseStatusException.class, () -> controller.save("Users.1", mapper.readTree("[{\"id\":\"x\",\"visible\":true,\"fixed\":\"invalid\"}]")));
            assertThrows(ResponseStatusException.class, () -> controller.save("Users.1", mapper.readTree("[{\"id\":\"x\",\"visible\":true,\"fixed\":\"\"},{\"id\":\"x\",\"visible\":true,\"fixed\":\"\"}]")));
            assertThrows(ResponseStatusException.class, () -> controller.get("../Users"));
            login("1", "ROLE_USER");
            assertThrows(ResponseStatusException.class, () -> controller.get("Users.1"));
            assertThrows(ResponseStatusException.class, () -> controller.save("Users.1", config));
            SecurityContextHolder.clearContext();
            assertThrows(ResponseStatusException.class, () -> controller.get("Users.1"));
        } finally { SecurityContextHolder.clearContext(); }
    }
}
