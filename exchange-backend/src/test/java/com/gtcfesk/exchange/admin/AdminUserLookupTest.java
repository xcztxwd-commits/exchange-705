package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.simulation.AdminReadRoutes;
import com.gtcfesk.exchange.simulation.SimulationEnvironment;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.persistence.EntityManager;
import javax.sql.DataSource;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"spring.datasource.url=jdbc:h2:mem:user-lookup;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = AdminUserLookupTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminUserLookupTest {
    @Configuration @AutoConfigurationPackage(basePackages = "com.gtcfesk.exchange.entity")
    @EntityScan(basePackageClasses = UserAccount.class)
    static class Config {}
    @Autowired EntityManager em;
    @Autowired DataSource dataSource;
    private final AdminPermissionService permissions = mock(AdminPermissionService.class);
    private UserAccountRepository users;
    private AdminUserLookupController controller;
    private JdbcTemplate jdbc;

    @BeforeEach void setup() {
        TenantContext.clear(); TenantContext.open(41L); login("ROLE_ADMIN", "1");
        TenantRepositoryFactoryBean<UserAccountRepository,UserAccount,Long> factory = new TenantRepositoryFactoryBean<>(UserAccountRepository.class);
        factory.setEntityManager(em); factory.afterPropertiesSet(); users = factory.getObject();
        controller = new AdminUserLookupController(users, permissions);
        jdbc = new JdbcTemplate(dataSource); jdbc.update("DELETE FROM user_account");
        insert(41, 7001, "Alice@Example.com", "normal", 50);
        insert(41, 17002, "bob@example.com", "normal", 60);
        insert(41, 77003, "alice_100%@example.com", "agent", 50);
        insert(42, 7004, "alice@other.example.com", "agent", 50);
    }
    @AfterEach void cleanup() { TenantContext.clear(); SecurityContextHolder.clearContext(); }
    private void login(String role, String id) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id, null, Collections.singletonList(new SimpleGrantedAuthority(role)))); }
    private void insert(long tenant, long id, String email, String type, long parent) {
        jdbc.update("INSERT INTO user_account(tenant_id,id,email,password_hash,user_type,parent_user_id,status,row_version,created_at,updated_at) VALUES(?,?,?,?,?,?,'normal',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", tenant, id, email, "not-for-search-results", type, parent);
    }
    private List<Long> ids(List<Map<String,Object>> rows) { return rows.stream().map(row -> ((Number)row.get("userId")).longValue()).collect(Collectors.toList()); }

    @Test void partialIdsAndEmailFragmentsAreTenantScopedAndWildcardsAreLiteral() {
        assertEquals(Arrays.asList(7001L, 17002L, 77003L), ids(controller.search("orders", "700", null)));
        List<Map<String,Object>> matches = controller.search("financial_orders", " ALICE ", null);
        assertEquals(Arrays.asList(7001L, 77003L), ids(matches));
        assertEquals(new HashSet<>(Arrays.asList("userId", "email")), matches.get(0).keySet());
        assertFalse(matches.toString().contains("not-for-search-results"));
        assertEquals(Collections.singletonList(77003L), ids(controller.search("kyc_review", "_100%", null)));
        assertTrue(controller.search("orders", " ", null).isEmpty());
        assertTrue(controller.search("orders", "missing", null).isEmpty());
    }

    @Test void agentCannotExpandOwnScopeAndAgentLookupOnlyReturnsAgents() {
        login("ROLE_AGENT", "agent_50");
        assertEquals(Arrays.asList(7001L, 77003L), ids(controller.search("loan_review", "example", 60L)));
        assertEquals(Collections.singletonList(77003L), ids(controller.search("agents", "example", null)));
        login("ROLE_ADMIN", "1");
        assertEquals(Collections.singletonList(17002L), ids(controller.search("deposit_review", "example", 60L)));
    }

    @Test void liveModulePermissionAlsoProtectsDemoForwarding() {
        doThrow(new AccessDeniedException("denied")).when(permissions).require("withdraw_review", "view");
        assertThrows(AccessDeniedException.class, () -> controller.search("withdraw_review", "alice", null));
        AdminAccountQueryController gateway = new AdminAccountQueryController(permissions, mock(SimulationEnvironment.class));
        ReflectionTestUtils.setField(gateway, "key", "");
        AdminReadRoutes.Query query = new AdminReadRoutes.Query(); query.method = "GET"; query.path = "/api/admin/user-lookup/withdraw_review";
        assertThrows(AccessDeniedException.class, () -> gateway.query(query, new MockHttpServletResponse()));
        query.path = "/api/admin/user-lookup/orders";
        assertThrows(ResponseStatusException.class, () -> gateway.query(query, new MockHttpServletResponse()));
        verify(permissions).require("orders", "view");
        for (String menu : Arrays.asList("users", "agents", "orders", "financial_orders", "loan_personal_info_review", "loan_review", "deposit_review", "withdraw_review", "kyc_review"))
            assertEquals(menu, AdminReadRoutes.permission("GET", "/api/admin/user-lookup/" + menu));
        assertThrows(IllegalArgumentException.class, () -> controller.search("settings", "alice", null));
        assertThrows(IllegalArgumentException.class, () -> AdminReadRoutes.permission("POST", query.path));
        assertThrows(IllegalArgumentException.class, () -> controller.search("orders", String.join("", Collections.nCopies(255, "x")), null));
    }

    @Test void lookupIsBoundedAndExistingDepositSearchStillWorks() {
        for (int i = 0; i < 25; i++) insert(41, 30000 + i, "bulk" + i + "@example.com", "normal", 50);
        assertEquals(20, controller.search("users", "bulk", null).size());
        assertEquals(Collections.singletonList(7001L), users.findDepositCustomers(null, "7001%", "%no-email%", PageRequest.of(0, 20)).stream().map(UserAccount::getId).collect(Collectors.toList()));
    }
}
