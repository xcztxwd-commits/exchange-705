package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.config.GlobalExceptionHandler;
import com.gtcfesk.exchange.simulation.AccountInspection;
import com.gtcfesk.exchange.user.UserActivityService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Rejected supervision writes remain HTTP 405 and never reach business collaborators. */
class ControlReadOnlyMethodTest {
    @Test void readonlyRegulatoryRoutesRejectWritesWithoutBusinessInvocations() throws Exception {
        UserActivityService activity=mock(UserActivityService.class);AccountInspection inspection=mock(AccountInspection.class);
        TenantRepository tenants=mock(TenantRepository.class);ControlAuditService audit=mock(ControlAuditService.class);
        JdbcTemplate jdbc=mock(JdbcTemplate.class);ControlReadQueryService queries=mock(ControlReadQueryService.class);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new ControlReadController(activity,inspection,tenants,audit,jdbc,queries))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        for(String route:new String[]{"/business/users","/support/conversations"}) {
            mvc.perform(post("/api/control/tenants/17"+route).contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow","GET"))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.success").value(false));
        }
        verifyNoInteractions(activity,inspection,tenants,audit,jdbc,queries);
    }
}
