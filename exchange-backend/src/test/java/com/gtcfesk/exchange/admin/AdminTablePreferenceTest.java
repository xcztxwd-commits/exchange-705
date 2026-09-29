package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.AdminTablePreference;
import com.gtcfesk.exchange.repository.AdminTablePreferenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminTablePreferenceTest {
    private void login(String id, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id, null,
                Collections.singletonList(new SimpleGrantedAuthority(role))));
    }
    @Test void isolatesAccountsAndTablesAndValidatesColumns() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AdminTablePreferenceRepository repository = mock(AdminTablePreferenceRepository.class);
        Map<String, AdminTablePreference> database = new HashMap<>();
        when(repository.findById(anyString())).thenAnswer(call -> Optional.ofNullable(database.get(call.getArgument(0))));
        when(repository.save(any())).thenAnswer(call -> { AdminTablePreference value = call.getArgument(0); database.put(value.getId(), value); return value; });
        AdminTablePreferenceController controller = new AdminTablePreferenceController(repository, mapper);
        try {
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
