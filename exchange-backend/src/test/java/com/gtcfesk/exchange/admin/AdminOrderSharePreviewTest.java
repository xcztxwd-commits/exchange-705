package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.simulation.AdminReadRoutes;
import com.gtcfesk.exchange.tenant.TenantOneFixture;
import com.gtcfesk.exchange.trade.ContractOrderService;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(TenantOneFixture.class)
class AdminOrderSharePreviewTest {
    private final ContractOrderRepository contracts = mock(ContractOrderRepository.class);
    private final OptionOrderRepository options = mock(OptionOrderRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AssetAccountRepository assets = mock(AssetAccountRepository.class);
    private final ContractOrderService trades = mock(ContractOrderService.class);
    private final SystemConfigService configs = mock(SystemConfigService.class);
    private final AdminOrderController controller = new AdminOrderController(contracts, options, trades, users, assets, mock(JwtUtil.class));
    private final ContractOrder contract = new ContractOrder();
    private final OptionOrder option = new OptionOrder();
    private final UserAccount owner = new UserAccount();

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        ReflectionTestUtils.setField(controller, "systemConfigService", configs);
        when(configs.getConfigValue("site.name")).thenReturn("Tenant Exchange");
        owner.setTenantId(1L); owner.setId(700L); owner.setParentUserId(42L);
        owner.setNickname("Order owner"); owner.setEmail("owner@example.com");
        owner.setPasswordHash("private-password"); owner.setRemark("private-remark"); owner.setMyInviteCode("private-invite");
        contract.setTenantId(1L); contract.setId(81L); contract.setUserId(700L); contract.setStatus("CLOSED");
        contract.setSymbol("JPY=X"); contract.setSide("SELL"); contract.setLeverage(new BigDecimal("100"));
        contract.setProfit(new BigDecimal("4828.58")); contract.setMargin(new BigDecimal("10000"));
        contract.setFee(new BigDecimal("123.45")); contract.setOpenPrice(new BigDecimal("158.189"));
        contract.setClosePrice(new BigDecimal("158.162")); contract.setCloseTime(LocalDateTime.of(2026, 10, 6, 1, 28));
        option.setTenantId(1L); option.setId(82L); option.setUserId(700L); option.setStatus("CLOSED");
        option.setSymbol("BTCUSDT"); option.setDirection("DOWN"); option.setAmount(new BigDecimal("100")); option.setProfit(new BigDecimal("-10.11"));
        when(contracts.findByTenantIdAndId(1L, 81L)).thenReturn(Optional.of(contract));
        when(options.findByTenantIdAndId(1L, 82L)).thenReturn(Optional.of(option));
        when(users.findByTenantIdAndId(1L, 700L)).thenReturn(Optional.of(owner));
    }

    @AfterEach void readOnly() {
        SecurityContextHolder.clearContext();
        verifyNoInteractions(assets, trades);
        for (Object repository : Arrays.asList(contracts, options, users))
            mockingDetails(repository).getInvocations().forEach(call -> assertTrue(call.getMethod().getName().startsWith("find"), "Preview must not write"));
    }

    @Test void usesRecordedSettlementAndOwningUserNotAdministrator() throws Exception {
        ResponseEntity<?> response = controller.sharePreview("contract", 81L);
        Map<?, ?> row = (Map<?, ?>) response.getBody();
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(700L, row.get("userId")); assertEquals("Order owner", row.get("userName")); assertEquals("owner@example.com", row.get("userEmail"));
        assertEquals("Tenant Exchange", row.get("brand"));
        assertEquals(new BigDecimal("4828.58"), row.get("profit")); assertEquals(new BigDecimal("123.45"), row.get("fee"));
        assertEquals(new BigDecimal("100"), row.get("leverage")); assertEquals(new BigDecimal("158.162"), row.get("closePrice"));
        assertFalse(row.toString().contains("private-"));
        Method method = AdminOrderController.class.getMethod("sharePreview", String.class, Long.class);
        AdminPermission permission = method.getAnnotation(AdminPermission.class);
        assertEquals("orders", permission.menu()); assertEquals("", permission.action());
        assertTrue(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
        for (BigDecimal profit : Arrays.asList(BigDecimal.ZERO, new BigDecimal("-10.11"))) {
            contract.setProfit(profit);
            assertEquals(profit, ((Map<?, ?>) controller.sharePreview("contract", 81L).getBody()).get("profit"));
        }
        row = (Map<?, ?>) controller.sharePreview("option", 82L).getBody();
        assertEquals(new BigDecimal("-10.11"), row.get("profit")); assertEquals(new BigDecimal("100"), row.get("amount"));
        assertEquals("DOWN", row.get("direction")); assertEquals(700L, row.get("userId"));
        assertEquals("Tenant Exchange", row.get("brand"));
        when(configs.getConfigValue("site.name")).thenReturn("Renamed Exchange");
        assertEquals("Renamed Exchange", ((Map<?, ?>) controller.sharePreview("contract", 81L).getBody()).get("brand"));
    }

    @Test void rejectsNonSettledDeletedUnboundAndMissingRecords() {
        contract.setStatus("OPEN"); assertThrows(BusinessException.class, () -> controller.sharePreview("contract", 81L));
        contract.setStatus("CLOSED"); contract.setDeletedAt(LocalDateTime.now());
        assertThrows(BusinessException.class, () -> controller.sharePreview("contract", 81L));
        contract.setDeletedAt(null); contract.setUserId(null);
        assertThrows(BusinessException.class, () -> controller.sharePreview("contract", 81L));
        assertThrows(BusinessException.class, () -> controller.sharePreview("other", 81L));
        assertThrows(BusinessException.class, () -> controller.sharePreview("contract", 999L));
        assertThrows(BusinessException.class, () -> controller.sharePreview("option", 999L));
        verifyNoInteractions(users);
        contract.setUserId(700L); when(users.findByTenantIdAndId(1L, 700L)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> controller.sharePreview("contract", 81L));
    }

    @Test void rejectsCrossTenantRecordsAndLimitsAgentsToOwnSubordinates() {
        ReflectionTestUtils.setField(contract, "tenantId", 2L);
        assertThrows(AccessDeniedException.class, () -> controller.sharePreview("contract", 81L));
        ReflectionTestUtils.setField(contract, "tenantId", 1L); ReflectionTestUtils.setField(owner, "tenantId", 2L);
        assertThrows(AccessDeniedException.class, () -> controller.sharePreview("contract", 81L));
        ReflectionTestUtils.setField(owner, "tenantId", 1L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent-42", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_AGENT"))));
        assertEquals(200, controller.sharePreview("contract", 81L).getStatusCodeValue());
        assertEquals(200, controller.sharePreview("option", 82L).getStatusCodeValue());
        owner.setParentUserId(43L);
        assertThrows(AccessDeniedException.class, () -> controller.sharePreview("contract", 81L));
        assertThrows(AccessDeniedException.class, () -> controller.sharePreview("option", 82L));
    }

    @Test void demoProxyAllowsOnlyExactReadRoutes() {
        for (String kind : Arrays.asList("contract", "option")) {
            String path = "/api/admin/orders/" + kind + "/81/share-preview";
            assertEquals("orders", AdminReadRoutes.permission("GET", path));
            assertThrows(IllegalArgumentException.class, () -> AdminReadRoutes.permission("POST", path));
            assertThrows(IllegalArgumentException.class, () -> AdminReadRoutes.permission("DELETE", path));
        }
        assertThrows(IllegalArgumentException.class, () -> AdminReadRoutes.permission("GET", "/api/admin/orders/contract/0/share-preview"));
        assertThrows(IllegalArgumentException.class, () -> AdminReadRoutes.permission("GET", "/api/admin/orders/contract/81/share-preview/edit"));
        assertThrows(IllegalArgumentException.class, () -> AdminReadRoutes.permission("GET", "/api/admin/orders/other/81/share-preview"));
    }

    @Test void mvcMapsBothReadRoutesAndRefusesPosts() throws Exception {
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        for (String route : Arrays.asList("contract/81", "option/82")) {
            String path = "/api/admin/orders/" + route + "/share-preview";
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.userName").value("Order owner"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isMethodNotAllowed());
        }
    }
}
