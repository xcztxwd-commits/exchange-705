package com.gtcfesk.exchange.security;

import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.auth.*;
import com.gtcfesk.exchange.auth.dto.*;
import com.gtcfesk.exchange.common.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.trade.*;
import com.gtcfesk.exchange.trade.dto.*;
import com.gtcfesk.exchange.user.*;
import com.gtcfesk.exchange.market.*;
import com.gtcfesk.exchange.service.EmailService;
import com.fasterxml.jackson.databind.*;
import io.jsonwebtoken.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Isolated database, no external market, mail, payment or production fixtures. */
@org.springframework.context.annotation.Import(com.gtcfesk.exchange.tenant.BootTenantFixture.class)
@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
@org.springframework.test.context.TestPropertySource(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:multitenant-market-test.sql","spring.redis.host=127.0.0.1", "spring.redis.port=${MT705_TEST_REDIS_PORT:1}", "spring.redis.password=${MT705_TEST_REDIS_PASSWORD:}", "platform.base-domain=mt705.test","platform.admin-origin=https://admin.mt705.test","platform.control-origin=https://control.mt705.test"})
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:minimal_fix;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.jpa.open-in-view=false", "logging.level.root=ERROR"})
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class MinimalFixRegressionTest {
    static final String SECRET = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes());
    static final String PASSWORD = UUID.randomUUID().toString();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("jwt.secret", () -> SECRET);
        if (System.getenv("QA_MINIMAL_MYSQL") != null) {
            r.add("spring.datasource.url", () -> System.getenv("QA_MINIMAL_MYSQL"));
            r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
            r.add("spring.datasource.username", () -> "root");
            r.add("spring.datasource.password", () -> System.getenv("QA_DB_PASSWORD"));
        } }
    @MockBean ForexQuoteMarketService quotes;
    @MockBean MarketInstrumentCatalog catalog;
    @MockBean MarketOrderProcessor processor;
    @MockBean RedisMarketService redis;
    @MockBean EmailService email;
    // Business-rule fixture only; real Redis/filter coverage lives in RealRegistrationFlowTest.
    @MockBean RegistrationSecurity registrationSecurity;

    // This pre-multitenant suite isolates its named business rules; live onboarding/readiness is separately exercised by TenantReadinessServiceTest.
    @MockBean com.gtcfesk.exchange.control.TenantReadinessService readiness;
    @Autowired com.gtcfesk.exchange.control.BackendLoginRegistry backendNames;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired KycRecordRepository identities;
    @Autowired LoanPersonalInfoRepository supplements;
    @Autowired AssetAccountRepository assets;
    @Autowired AdminUserRepository admins;
    @Autowired AdminMenuRepository menus;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminRoleMenuRepository roleMenus;
    @Autowired UserMenuRepository userMenus;
    @Autowired UserActionRepository actions;
    @Autowired UserBankCardRepository cards;
    @Autowired UserDigitalAddressRepository addresses;
    @Autowired VerifyCodeRepository codes;
    @Autowired TransferRecordRepository transfers;
    @Autowired LoanRecordRepository loans;
    @Autowired DepositRecordRepository deposits;
    @Autowired FinancialOrderRepository financialOrders;
    @Autowired ContractOrderRepository contracts;
    @Autowired OptionOrderRepository options;
    @Autowired TradingSymbolRepository symbols;
    @Autowired OptionDurationRepository durations;
    @Autowired AnnouncementRepository announcements;
    @Autowired OperationLogRepository logs;
    @Autowired AuthService auth;
    @Autowired AdminAuthService adminAuth;
    @Autowired AdminUserService adminUsers;
    @Autowired LoanService loanService;
    @Autowired LoanReviewService loanReview;
    @Autowired DepositReviewService depositReview;
    @Autowired ContractOrderService contractService;
    @Autowired OptionOrderService optionService;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtUtil jwt;
    @Autowired PlatformTransactionManager manager;
    UserAccount a, b;
    String ta, tb, superToken;
    String prefix;
    @BeforeEach void setup() {
        prefix = "fix_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        a = user("a", "normal", null); b = user("b", "normal", null);
        identity(a, "APPROVED"); // Existing trading scenarios explicitly use an approved trader; b remains unverified.
        ta = login(a); tb = login(b);
        superToken = admin("super_admin");
    }
    UserAccount user(String suffix, String type, Long parent) {
        UserAccount u = new UserAccount(); u.setEmail(prefix + suffix + "@example.invalid"); u.setNickname(suffix);
        u.setPasswordHash(encoder.encode(PASSWORD)); u.setUserType(type); u.setParentUserId(parent);
        u = users.saveAndFlush(u);
        for (String coin : Arrays.asList("FUND", "CONTRACT", "OPTION")) {
            AssetAccount account = new AssetAccount(); account.setUserId(u.getId()); account.setCoin(coin); account.setAvailable(new BigDecimal("10000")); assets.saveAndFlush(account);
        }
        return u;
    }
    String login(UserAccount u) { LoginRequest request = new LoginRequest(); request.setAccount(u.getEmail()); request.setPassword(PASSWORD); return auth.login(request).getToken(); }
    String agentLogin(UserAccount u) { backendNames.register("AGENT",u.getId(),u.getEmail()); LoginRequest request = new LoginRequest(); request.setAccount(u.getEmail()); request.setPassword(PASSWORD); return adminAuth.login(request).getToken(); }
    String admin(String role) {
        AdminUser u = new AdminUser();u.setAccount(prefix+role);u.setEmail(prefix+role+"@example.invalid");u.setRole(role);u.setPasswordHash(encoder.encode(PASSWORD));admins.saveAndFlush(u);backendNames.register("ADMIN",u.getId(),u.getAccount());
        LoginRequest req = new LoginRequest();req.setAccount(u.getAccount());req.setPassword(PASSWORD);return adminAuth.login(req).getToken();
    }
    Map<String,Object> map(Object... values) {Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)m.put(values[i].toString(),values[i+1]);return m;}
    MvcResult request(String method,String path,String token,Object body) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path);
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(body!=null)builder.contentType("application/json").content(json.writeValueAsBytes(body));
        return mvc.perform(builder).andReturn();
    }
    int status(MvcResult result){return result.getResponse().getStatus();}
    JsonNode body(MvcResult result)throws Exception{return json.readTree(result.getResponse().getContentAsByteArray());}
    BigDecimal balance(UserAccount u,String coin){return assets.findByTenantIdAndUserIdAndCoin(1L, u.getId(),coin).get().getAvailable();}
    void same(BigDecimal expected,BigDecimal actual){assertEquals(0,expected.compareTo(actual));}
    AdminMenu menu(String code) {return menus.findByMenuCode(code).orElseGet(()->{AdminMenu m=new AdminMenu();m.setMenuCode(code);m.setMenuName(code);m.setMenuType("menu");return menus.saveAndFlush(m);});}
    void grant(UserAccount agent,String code,String... operations){AdminMenu m=menu(code);UserMenu um=new UserMenu();um.setUserId(agent.getId());um.setMenuId(m.getId());userMenus.saveAndFlush(um);for(String operation:operations){UserAction action=new UserAction();action.setUserId(agent.getId());action.setMenuId(m.getId());action.setActionCode(operation);actions.saveAndFlush(action);}}
    @Test void websiteSecurityIsSuperAdminOnlyAndPersistsValidatedPolicy() throws Exception {
        String url = "/api/admin/website-security";
        assertEquals(401, status(request("GET",url,null,null)));
        assertEquals(401, status(request("GET",url,ta,null)));
        String ordinary = admin("admin");
        assertEquals(403, status(request("GET",url,ordinary,null)));
        assertEquals(403, status(request("PUT",url,ordinary,map("registerIpPerMinute",2))));
        UserAccount agent = user("securityagent", "agent", null);
        assertEquals(403, status(request("GET",url,agentLogin(agent),null)));
        assertEquals(200, status(request("GET",url,superToken,null)));
        assertEquals(200, status(request("PUT",url,superToken,map("captchaIpPerMinute",41))));
        assertEquals(41, body(request("GET",url,superToken,null)).path("captchaIpPerMinute").asInt());
        assertEquals(400, status(request("PUT",url,superToken,map("captchaIpPerMinute",0))));
        assertEquals(400, status(request("PUT",url,superToken,map("captchaIpPerMinute",1.5))));
        assertEquals(400, status(request("PUT",url,superToken,map("unexpected",3))));
        assertEquals(41, body(request("GET",url,superToken,null)).path("captchaIpPerMinute").asInt());
        assertEquals(200, status(request("PUT",url,superToken,map("captchaIpPerMinute",30))));
    }
    @Test void anonymousAndUserCannotUseAdminOrCollideWithAdminIdentity() throws Exception {
        BigDecimal before=balance(a,"FUND");
        assertEquals(401,status(request("GET","/api/admin/users",null,null)));
        assertEquals(401,status(request("POST","/api/admin/users/updateBalance",ta,map("userId",a.getId(),"balance",9))));
        assertEquals(401,status(request("POST","/api/admin/admins",ta,map("role","super_admin"))));
        assertEquals(401,status(request("GET","/api/user/assets",superToken,null)));
        same(before,balance(a,"FUND"));assertEquals(200,status(request("GET","/api/admin/users",superToken,null)));
    }
    @Test void ordinaryAdminUsesOnlyAssignedMenusAndCannotGrantPrivileges()throws Exception{
        String code=prefix+"role";AdminRole role=new AdminRole();role.setRoleCode(code);role.setRoleName(code);role=roles.saveAndFlush(role);
        AdminRoleMenu rm=new AdminRoleMenu();rm.setRoleId(role.getId());rm.setMenuId(menu("users").getId());roleMenus.saveAndFlush(rm);
        String token=admin(code);
        assertEquals(200,status(request("GET","/api/admin/users",token,null)));
        assertEquals(403,status(request("GET","/api/admin/operation-logs",token,null)));
        assertEquals(403,status(request("POST","/api/admin/users/updateBalance",token,map("userId",a.getId(),"fundBalance",1))));
        JsonNode counts=body(request("GET","/api/admin/notification/pending-counts",token,null)).path("data");
        assertEquals(0,counts.path("deposit").asLong());
        JsonNode sounds=body(request("GET","/api/admin/notification/sounds",token,null));
        assertEquals(4,sounds.size());assertTrue(sounds.get(0).path("configKey").asText().startsWith("notification.sound."));
        assertEquals(403,status(request("GET","/api/admin/config/list",token,null)));
        String noMenus=admin(prefix+"noMenus");
        assertEquals(0,body(request("GET","/api/admin/users/online-count",noMenus,null)).path("count").asInt());
        assertEquals(403,status(request("POST","/api/admin/admins",token,map("role","super_admin"))));
        assertEquals(403,status(request("POST","/api/admin/config/save",token,map("key","blocked","value","x"))));
    }
    @Test void agentsKeepGrantedFunctionsButCannotOverrideScopeOrActions()throws Exception{
        UserAccount aa=user("aa","agent",null), ab=user("ab","agent",null);
        a=users.findByTenantIdAndId(1L, a.getId()).get(); b=users.findByTenantIdAndId(1L, b.getId()).get();
        a.setParentUserId(aa.getId());users.saveAndFlush(a);b.setParentUserId(ab.getId());users.saveAndFlush(b);
        grant(aa,"users","reset_password");grant(ab,"users");String at=agentLogin(aa),bt=agentLogin(ab);
        assertEquals(4,body(request("GET","/api/admin/notification/sounds",at,null)).size());
        JsonNode list=body(request("POST","/api/admin/users/query",at,map("page",0,"size",20))).get("list");
        assertEquals(1,list.size());assertEquals(a.getId().longValue(),list.get(0).get("id").asLong());
        assertEquals(403,status(request("POST","/api/admin/users/query",at,map("filterAgentId",ab.getId(),"page",0,"size",20))));
        assertEquals(403,status(request("GET","/api/admin/users/"+b.getId(),at,null)));
        String prior=users.findByTenantIdAndId(1L, b.getId()).get().getPasswordHash();
        assertEquals(403,status(request("POST","/api/admin/users/resetPassword",at,map("userId",b.getId(),"newPassword",PASSWORD))));
        assertEquals(prior,users.findByTenantIdAndId(1L, b.getId()).get().getPasswordHash());
        assertEquals(200,status(request("POST","/api/admin/users/resetPassword",at,map("userId",a.getId(),"newPassword",PASSWORD))));
        assertEquals(403,status(request("POST","/api/admin/users/resetPassword",bt,map("userId",b.getId(),"newPassword",PASSWORD))));
    }
    @Test void oldForgedExpiredDisabledAndRevokedSessionsAreRejected()throws Exception{
        assertEquals(401,status(request("GET","/api/user/assets","mock-"+a.getId(),null)));
        assertEquals(401,status(request("GET","/api/user/assets",ta+"x",null)));
        assertEquals(200,status(request("POST","/api/auth/login","invalid",map("account",b.getEmail(),"password",PASSWORD))));
        UserAccount current=users.findByTenantIdAndId(1L, a.getId()).get();
        String expired=Jwts.builder().setSubject("user-"+a.getId()).claim("userType","user").claim("sid",current.getCurrentToken()).claim("credential",jwt.credentialKey(current.getPasswordHash())).setExpiration(new Date(System.currentTimeMillis()-10000)).signWith(SignatureAlgorithm.HS256,SECRET).compact();
        assertEquals(401,status(request("GET","/api/user/assets",expired,null)));
        com.gtcfesk.exchange.admin.dto.UpdateUserStatusRequest req=new com.gtcfesk.exchange.admin.dto.UpdateUserStatusRequest();req.setUserId(a.getId());req.setStatus("disabled");adminUsers.updateStatus(req);
        assertEquals(401,status(request("GET","/api/user/assets",ta,null)));assertThrows(BusinessException.class,()->login(a));
        req.setStatus("normal");adminUsers.updateStatus(req);assertEquals(401,status(request("GET","/api/user/assets",ta,null)));
        assertEquals(200,status(request("GET","/api/user/assets",login(a),null)));
    }
    VerifyCode code(UserAccount user,String scene){VerifyCode c=new VerifyCode();c.setEmail(user.getEmail());c.setScene(scene);c.setCode(String.valueOf(100000 + new java.security.SecureRandom().nextInt(900000)));c.setExpireAt(LocalDateTime.now().plusMinutes(5));return codes.saveAndFlush(c);}
    @Test void resetCodeConcurrentConsumptionHasOneWinnerAndRevokesSession()throws Exception{
        VerifyCode old=code(a,"forget_password"), latest=code(a,"forget_password");
        ResetPasswordRequest req=new ResetPasswordRequest();req.setEmail(a.getEmail());req.setPassword(PASSWORD);req.setConfirmPassword(PASSWORD);req.setVerifyCode(latest.getCode());
        List<Boolean> result=race(()->{auth.resetPassword(req);return true;},()->{auth.resetPassword(req);return true;});
        assertEquals(1,result.stream().filter(Boolean.TRUE::equals).count());
        assertEquals(401,status(request("GET","/api/user/assets",ta,null)));
        req.setVerifyCode(old.getCode());assertThrows(BusinessException.class,()->auth.resetPassword(req));
        assertEquals(200,status(request("GET","/api/user/assets",login(a),null)));
    }
    @Test void registrationWithOnlyEmailAndPasswordIsRejected() throws Exception {
        String address = prefix + "twofields@example.invalid";
        Map<String,Object> req = map("email", address, "password", PASSWORD);
        assertEquals(400, status(request("POST", "/api/auth/register", null, req)));
        assertFalse(users.existsByTenantIdAndEmail(1L, address));
    }

    @Test void registrationWithoutInvitationOrEmailCodeCreatesUsableAccount() throws Exception {
        String address = prefix + "nocode@example.invalid";
        Map<String,Object> req = map("email", address, "password", PASSWORD, "confirmPassword", PASSWORD);
        req.put("captchaSession", "0123456789abcdef0123456789abcdef");
        req.put("captchaId", "0123456789abcdef0123456789abcdef"); req.put("captchaCode", "A2B3");
        assertFalse(codes.findAllByTenantId(1L).stream().anyMatch(code -> address.equals(code.getEmail())));
        MvcResult result = request("POST", "/api/auth/register", null, req);
        assertEquals(200, status(result));
        UserAccount created = users.findByTenantIdAndEmail(1L, address).orElseThrow(AssertionError::new);
        assertTrue(encoder.matches(PASSWORD, created.getPasswordHash()));
        assertNotEquals(PASSWORD, created.getPasswordHash());
        assertNull(created.getParentUserId());
        assertNull(created.getInviteCode());
        assertNotNull(created.getMyInviteCode());
        assertEquals(3, assets.findByTenantIdAndUserId(1L, created.getId()).size());
        for (String coin : Arrays.asList("FUND", "CONTRACT", "OPTION")) {
            assertTrue(assets.findByTenantIdAndUserIdAndCoin(1L, created.getId(), coin).isPresent());
        }
        MvcResult loggedIn = request("POST", "/api/auth/login", null, map("account", address, "password", PASSWORD));
        assertEquals(200, status(loggedIn));
        String token = body(loggedIn).path("token").asText();
        assertFalse(token.isEmpty());
        assertEquals(200, status(request("GET", "/api/user/assets", token, null)));
        assertEquals(400, status(request("POST", "/api/auth/register", null, req)));
        assertFalse(codes.findAllByTenantId(1L).stream().anyMatch(code -> address.equals(code.getEmail())));
    }

    @Test void registerRejectsMismatchedConfirmationWithoutCreatingUser()throws Exception{
        UserAccount candidate=new UserAccount();candidate.setEmail(prefix+"new@example.invalid");VerifyCode code=code(candidate,"register");
        Map<String,Object> req=map("email",candidate.getEmail(),"password",PASSWORD,"confirmPassword",PASSWORD+"x","verifyCode",code.getCode());
        req.put("captchaSession", "0123456789abcdef0123456789abcdef");
        req.put("captchaId", "0123456789abcdef0123456789abcdef"); req.put("captchaCode", "A2B3");
        assertEquals(400,status(request("POST","/api/auth/register",null,req)));assertFalse(users.existsByTenantIdAndEmail(1L, candidate.getEmail()));
        req.put("confirmPassword",PASSWORD);assertEquals(200,status(request("POST","/api/auth/register",null,req)));assertTrue(users.existsByTenantIdAndEmail(1L, candidate.getEmail()));
    }
    @Test void walletCreationCannotOverwriteOtherOwnersAndUpdatesRejectForeignIds()throws Exception{
        Map<String,Object> card=map("currency","USD","bankName","test","recipientName","QA","recipientAccount",prefix);
        long id=body(request("POST","/api/wallet/bank-cards",tb,card)).path("data").path("id").asLong();assertTrue(id>0);
        card.put("id",id);card.put("userId",b.getId());
        long created=body(request("POST","/api/wallet/bank-cards",ta,card)).path("data").path("id").asLong();assertTrue(created>0);assertNotEquals(id,created);assertEquals(b.getId(),cards.findByTenantIdAndId(1L, id).get().getUserId());
        assertEquals(400,status(request("PUT","/api/wallet/bank-cards/"+id,ta,card)));assertEquals(400,status(request("DELETE","/api/wallet/bank-cards/"+id,ta,null)));assertTrue(cards.existsByTenantIdAndId(1L, id));
        Map<String,Object> address=map("currency","USDT","network","USDT-TRC20","address",prefix);
        id=body(request("POST","/api/wallet/digital-addresses",tb,address)).path("data").path("id").asLong();assertTrue(id>0);address.put("id",id);address.put("userId",b.getId());
        created=body(request("POST","/api/wallet/digital-addresses",ta,address)).path("data").path("id").asLong();assertNotEquals(id,created);assertEquals(b.getId(),addresses.findByTenantIdAndId(1L, id).get().getUserId());
        assertEquals(400,status(request("DELETE","/api/wallet/digital-addresses/"+id,ta,null)));assertTrue(addresses.existsByTenantIdAndId(1L, id));
    }
    LoanRecord loan(UserAccount owner,String state){LoanRecord l=new LoanRecord();l.setUserId(owner.getId());l.setAmount(new BigDecimal("10"));l.setDays(10);l.setDailyRate(BigDecimal.ZERO);l.setTotalInterest(BigDecimal.ZERO);l.setRepaymentAmount(l.getAmount());l.setStatus(state);return loans.saveAndFlush(l);}
    @Test void loanSigningAndRejectionRespectOwnershipAndSourceState()throws Exception{
        LoanRecord l=loan(a,"PENDING");
        assertEquals(400,status(request("POST","/api/loan/sign",tb,map("loanId",l.getId(),"signatureImage","test"))));assertFalse(loans.findByTenantIdAndId(1L, l.getId()).get().getContractSigned());
        assertEquals(200,status(request("POST","/api/loan/sign",ta,map("loanId",l.getId(),"signatureImage","test"))));
        loanReview.rejectLoan(l.getId(),"first");LocalDateTime time=loans.findByTenantIdAndId(1L, l.getId()).get().getUpdatedAt();loanReview.rejectLoan(l.getId(),"second");assertEquals("first",loans.findByTenantIdAndId(1L, l.getId()).get().getRemark());assertEquals(time,loans.findByTenantIdAndId(1L, l.getId()).get().getUpdatedAt());
        for(String state:Arrays.asList("APPROVED","COMPLETED","OVERDUE")){LoanRecord item=loan(a,state);assertThrows(BusinessException.class,()->loanReview.rejectLoan(item.getId(),"reject"));assertEquals(state,loans.findByTenantIdAndId(1L, item.getId()).get().getStatus());}
    }
    @Test void transferRacesConserveBalancesAndRetryKeysAreIdempotent()throws Exception{
        BigDecimal before=balance(a,"FUND"), target=balance(a,"CONTRACT");long count=transfers.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, a.getId()).size();
        for(int i=0;i<3;i++) {
            List<Boolean> outcomes=race(()->transfer("FUND","CONTRACT",10,null),()->transfer("FUND","CONTRACT",20,null));assertTrue(outcomes.stream().allMatch(Boolean.TRUE::equals));
        }
        same(before.subtract(new BigDecimal("90")),balance(a,"FUND"));same(target.add(new BigDecimal("90")),balance(a,"CONTRACT"));assertEquals(count+6,transfers.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, a.getId()).size());
        List<Boolean> inverse=race(()->transfer("FUND","CONTRACT",3,null),()->transfer("CONTRACT","FUND",3,null));assertTrue(inverse.stream().allMatch(Boolean.TRUE::equals));
        List<Boolean> duplicate=race(()->transfer("FUND","CONTRACT",7,"same"),()->transfer("FUND","CONTRACT",7,"same"));assertTrue(duplicate.stream().allMatch(Boolean.TRUE::equals));same(before.subtract(new BigDecimal("97")),balance(a,"FUND"));
        assertEquals(400,status(request("POST","/api/transfer/submit",ta,map("fromAccount","FUND","toAccount","fund","amount",1))));
        assertFalse(transfer("FUND","CONTRACT",20000,null));same(before.subtract(new BigDecimal("97")),balance(a,"FUND"));
    }
    boolean transfer(String from,String to,int amount,String key){try{return status(request("POST","/api/transfer/submit",ta,map("fromAccount",from,"toAccount",to,"amount",amount,"requestId",key)))==200;}catch(Exception e){throw new RuntimeException(e);}}
    List<Boolean> race(Supplier<Boolean> one,Supplier<Boolean> two)throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<Boolean>> work=new ArrayList<>();for(Supplier<Boolean> f:Arrays.asList(one,two))work.add(pool.submit(()->{start.await();try(com.gtcfesk.exchange.tenant.TenantContext.Scope ignored=com.gtcfesk.exchange.tenant.TenantContext.open(1L)){return f.get();}catch(RuntimeException e){return false;}}));start.countDown();List<Boolean> result=new ArrayList<>();for(Future<Boolean> f:work)result.add(f.get(20,TimeUnit.SECONDS));return result;}finally{pool.shutdownNow();}
    }
    @Test void staleAssetUpdatesRollbackBusinessRecords() {
        AssetAccount first=assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(),"FUND").get(), stale=assets.findByTenantIdAndId(1L, first.getId()).get();
        first.setAvailable(first.getAvailable().add(BigDecimal.ONE));assets.saveAndFlush(first);
        long before=transfers.countByTenantId(1L);
        assertThrows(RuntimeException.class,()->new TransactionTemplate(manager).execute(status->{TransferRecord t=new TransferRecord();t.setUserId(a.getId());t.setFromAccount("FUND");t.setToAccount("OPTION");t.setAmount(BigDecimal.ONE);transfers.saveAndFlush(t);stale.setAvailable(BigDecimal.ZERO);assets.saveAndFlush(stale);return null;}));
        same(new BigDecimal("10001"),balance(a,"FUND"));assertEquals(before,transfers.countByTenantId(1L));
    }
    @Test void invalidAmountsAndWithdrawalTypesDoNotWriteFundsOrRecords()throws Exception{
        org.mockito.Mockito.when(quotes.requireConversionRate("USD","yahoo")).thenReturn(BigDecimal.ONE);
        long count=deposits.countByTenantId(1L);BigDecimal before=balance(a,"FUND");
        for(int amount:new int[]{0,-1})assertEquals(400,status(request("POST","/api/deposit/submit",ta,map("type","digital","network","USDT-TRC20","address","test","amount",amount,"proofImage","test"))));
        assertEquals(count,deposits.countByTenantId(1L));assertEquals(400,status(request("POST","/api/withdraw/submit",ta,map("type","unknown","network","USD","address","test","amount",1))));same(before,balance(a,"FUND"));
        assertEquals(200,status(request("POST","/api/deposit/submit",ta,map("type","digital","network","USDT-TRC20","address","test","amount",1,"proofImage","test"))));assertEquals(count+1,deposits.countByTenantId(1L));
    }

    KycRecord identity(UserAccount user, String status) {
        KycRecord record = identities.findFirstByTenantIdAndUserIdOrderByCreatedAtDesc(1L, user.getId()).orElseGet(KycRecord::new);
        record.setUserId(user.getId()); record.setRealName("Synthetic trader"); record.setIdNumber("TEST-" + user.getId());
        record.setIdFrontImage("/uploads/test-front.png"); record.setIdBackImage("/uploads/test-back.png");
        record.setStatus(status); return identities.saveAndFlush(record);
    }
    @Test void newOrdersRequireAuditedIdentityAndNeverMoveUnverifiedFunds() throws Exception {
        TradingSymbol s = symbol(); quote(s, "100");
        // A cached user flag (or approved loan supplement) must not grant trading permission.
        UserAccount cachedUser = users.findByTenantIdAndId(1L, b.getId()).get(); cachedUser.setKycStatus("VERIFIED"); users.saveAndFlush(cachedUser);
        LoanPersonalInfo supplement = new LoanPersonalInfo(); supplement.setUserId(b.getId());
        supplement.setRealName("Synthetic trader"); supplement.setIdNumber("TEST-" + b.getId()); supplement.setStatus("APPROVED");
        supplement.setPhone("+819012345678"); supplement.setAddress("Test address"); supplements.saveAndFlush(supplement);
        for (String state : Arrays.asList("NONE", "PENDING", "REJECTED")) {
            if (!"NONE".equals(state)) identity(b, state);
            JsonNode info = body(request("GET", "/api/kyc/status", tb, null));
            assertFalse(info.path("canTrade").asBoolean()); assertEquals("NOT_VERIFIED", info.path("kycStatus").asText());
            for (String type : Arrays.asList("MARKET", "LIMIT")) for (String side : Arrays.asList("BUY", "SELL")) {
                MvcResult denied = request("POST", "/api/trade/contract/order", tb, map("symbol",s.getSymbol(),"type",type,"side",side,"quantity","0.01","price",100));
                assertEquals(403, status(denied)); assertEquals("KYC_REQUIRED", body(denied).path("errorCode").asText());
                assertEquals("NONE".equals(state) ? "NOT_VERIFIED" : state, body(denied).path("kycStatus").asText());
            }
            for (String direction : Arrays.asList("UP", "DOWN")) {
                MvcResult denied = request("POST", "/api/trade/option/order", tb, map("symbol",s.getSymbol(),"direction",direction,"duration",60,"amount",10));
                assertEquals(403, status(denied)); assertEquals("KYC_REQUIRED", body(denied).path("errorCode").asText());
            }
            assertTrue(contracts.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, b.getId()).isEmpty());
            assertTrue(options.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, b.getId()).isEmpty());
            for (AssetAccount account : assets.findByTenantIdAndUserId(1L, b.getId())) { same(new BigDecimal("10000"),account.getAvailable()); same(BigDecimal.ZERO,account.getFrozen()); }
        }
        KycRecord pendingIdentity = identity(b,"PENDING");
        assertEquals(200,status(request("POST","/api/admin/kyc/"+pendingIdentity.getId()+"/approve",superToken,map())));
        assertTrue(body(request("GET","/api/kyc/status",tb,null)).path("canTrade").asBoolean());
        assertEquals(200,status(request("POST","/api/trade/contract/order",tb,map("symbol",s.getSymbol(),"type","MARKET","side","BUY","quantity","0.01"))));
        durations.findByTenantIdAndDuration(1L, 60).orElseGet(() -> {
            OptionDuration d=new OptionDuration();d.setDuration(60);d.setLabel("60s");d.setEnabled(true);d.setSortOrder(1);
            d.setProfitRate(new BigDecimal("0.8"));d.setLossRate(BigDecimal.ONE);d.setMinAmount(BigDecimal.ONE);d.setMaxAmount(new BigDecimal("100"));return durations.saveAndFlush(d);
        });
        for (String direction : Arrays.asList("UP","DOWN")) assertEquals(200,status(request("POST","/api/trade/option/order",tb,map("symbol",s.getSymbol(),"direction",direction,"duration",60,"amount",10))));
        identity(b,"REJECTED");
        for (OptionOrder order : options.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, b.getId())) {order.setOpenTime(LocalDateTime.now().minusMinutes(2));options.saveAndFlush(order);}
        optionService.settleExpiredOrders(Collections.emptyMap());
        for (OptionOrder order : options.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, b.getId())) assertEquals("CLOSED",order.getStatus());
        same(BigDecimal.ZERO,assets.findByTenantIdAndUserIdAndCoin(1L, b.getId(),"OPTION").get().getFrozen());
        // Same login token works immediately after approval; settlement never requires re-verification.
    }
    @Test void pendingMatchingChecksIdentityButCancellationAndClosingRemainAvailable() {
        TradingSymbol s = symbol(); quote(s,"100");
        ContractOrder open = limit(s,"BUY","100"); assertEquals(1,contractService.matchPendingLimitOrders());
        ContractOrder modern = limit(s,"BUY","100"), legacy = limit(s,"BUY","100");
        legacy.setLotSize(null); contracts.saveAndFlush(legacy);
        identity(a,"REJECTED");
        AssetAccount before = assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(),"CONTRACT").get();
        assertEquals(0,contractService.matchPendingLimitOrders());
        for (ContractOrder order : Arrays.asList(modern,legacy)) assertEquals("PENDING",contracts.findByTenantIdAndId(1L, order.getId()).get().getStatus());
        AssetAccount after = assets.findByTenantIdAndId(1L, before.getId()).get(); same(before.getAvailable(),after.getAvailable()); same(before.getFrozen(),after.getFrozen());
        assertEquals("CLOSED",contractService.closeOrder(a.getId(),open.getId(),null).getStatus());
        contractService.cancelOrder(a.getId(),modern.getId()); contractService.cancelOrder(a.getId(),legacy.getId());
        same(BigDecimal.ZERO,assets.findByTenantIdAndId(1L, before.getId()).get().getFrozen());
    }

    TradingSymbol symbol(){TradingSymbol s=new TradingSymbol();s.setSymbol(prefix);s.setBaseCurrency("TEST");s.setName("QA");s.setSourceCategory("US");s.setMarketSource("yahoo");return symbols.saveAndFlush(s);}
    @Test void invalidTradeParametersLeaveBalancesAndOrdersUntouched()throws Exception{
        TradingSymbol symbol=symbol();OptionDuration d=durations.findByTenantIdAndDuration(1L, 60).orElseGet(()->{OptionDuration x=new OptionDuration();x.setDuration(60);x.setLabel("60s");x.setSortOrder(1);x.setEnabled(true);x.setProfitRate(new BigDecimal("0.8"));x.setLossRate(BigDecimal.ONE);x.setMinAmount(BigDecimal.ONE);x.setMaxAmount(new BigDecimal("100"));return durations.saveAndFlush(x);});
        Map<String,Object> req=map("symbol",symbol.getSymbol(),"side","BUY","type","MARKET","quantity",1,"currentPrice",100);
        quote(symbol,"100");
        for(Object[] bad:Arrays.asList(new Object[]{"side","INVALID"},new Object[]{"type","INVALID"},new Object[]{"quantity",-1},new Object[]{"quantity",0})) {Map<String,Object> input=new HashMap<>(req);input.put(bad[0].toString(),bad[1]);assertEquals(400,status(request("POST","/api/trade/contract/order",ta,input)));}
        Map<String,Object> opt=map("symbol",symbol.getSymbol(),"direction","UP","duration",60,"amount",10,"currentPrice",100);
        for(Object[] bad:Arrays.asList(new Object[]{"direction","INVALID"},new Object[]{"duration",-1},new Object[]{"duration",61},new Object[]{"amount",-1},new Object[]{"amount",0},new Object[]{"amount",101})) {Map<String,Object> input=new HashMap<>(opt);input.put(bad[0].toString(),bad[1]);assertEquals(400,status(request("POST","/api/trade/option/order",ta,input)));}
        assertEquals(0,contracts.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, a.getId()).size());assertEquals(0,options.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, a.getId()).size());same(new BigDecimal("10000"),balance(a,"CONTRACT"));same(new BigDecimal("10000"),balance(a,"OPTION"));
        assertEquals(200,status(request("POST","/api/trade/contract/order",ta,req)));assertEquals(200,status(request("POST","/api/trade/option/order",ta,opt)));
    }
    @Test void userInfoIsPrivateAndInputErrorsDoNotExposeInternals()throws Exception{
        assertEquals(403,status(request("GET","/api/user/"+b.getId()+"/info",ta,null)));assertEquals(200,status(request("GET","/api/user/"+a.getId()+"/info",ta,null)));
        MvcResult result=request("POST","/api/auth/login",null,map());assertEquals(400,status(result));String text=result.getResponse().getContentAsString();assertFalse(text.contains("java."));assertFalse(text.contains("Exception"));
        assertEquals(400,status(request("GET","/api/user/not-a-number/info",ta,null)));
    }
    @Test void deletionProtectsBalancesAndHistoryButAllowsUnusedAccounts()throws Exception{
        assertEquals(400,status(request("DELETE","/api/admin/users/"+a.getId(),superToken,null)));
        assertThrows(BusinessException.class,()->adminUsers.deleteUser(a.getId()));assertTrue(users.existsByTenantIdAndId(1L, a.getId()));
        for(AssetAccount account:assets.findByTenantIdAndUserId(1L, a.getId())){account.setAvailable(BigDecimal.ZERO);assets.saveAndFlush(account);}
        loan(a,"REJECTED");assertThrows(BusinessException.class,()->adminUsers.deleteUser(a.getId()));assertTrue(users.existsByTenantIdAndId(1L, a.getId()));
        for(AssetAccount account:assets.findByTenantIdAndUserId(1L, b.getId())){account.setAvailable(BigDecimal.ZERO);assets.saveAndFlush(account);}
        adminUsers.deleteUser(b.getId());assertFalse(users.existsByTenantIdAndId(1L, b.getId()));assertTrue(assets.findByTenantIdAndUserId(1L, b.getId()).isEmpty());
    }
    @Test void numericSymbolIdsAndMultipleAnnouncementsWork()throws Exception{
        TradingSymbol s=symbol();assertEquals(200,status(request("POST","/api/admin/symbols/batchSetLeverage",superToken,map("leverage",20,"symbolIds",Arrays.asList(s.getId())))));same(new BigDecimal("20"),symbols.findByTenantIdAndId(1L, s.getId()).get().getMaxLeverage());
        for(int i=0;i<2;i++){Announcement n=new Announcement();n.setTitle(prefix+i);n.setContent("test");n.setLanguage("en");n.setStatus("PUBLISHED");n.setPriority(i);announcements.saveAndFlush(n);}
        assertTrue(announcements.findLatestPublishedByLanguage("en").isPresent());assertEquals(200,status(request("GET","/api/user/announcements/latest?language=en",null,null)));
    }
    @Test void uploadDecodesContentAndNeverTrustsMimeOrExtension()throws Exception{
        MockMultipartFile fake=new MockMultipartFile("file","fake.txt","image/png","text only".getBytes());
        assertEquals(400,mvc.perform(multipart("/api/upload/image").file(fake).header("Authorization","Bearer "+ta)).andReturn().getResponse().getStatus());
        java.awt.image.BufferedImage png=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(png,"png",bytes);
        MockMultipartFile valid=new MockMultipartFile("file","image.txt","image/png",bytes.toByteArray());MvcResult result=mvc.perform(multipart("/api/upload/image").file(valid).header("Authorization","Bearer "+ta)).andReturn();assertEquals(200,status(result));assertTrue(body(result).path("url").asText().endsWith(".png"));assertFalse(body(result).has("filePath"));
    }
    @Test void logsRedactBeforeDatabasePersistence()throws Exception{
        String raw=json.writeValueAsString(map("password",PASSWORD,"nested",map("verifyCode",PASSWORD,"token",PASSWORD),"key","mail.password","value",PASSWORD));String safe=LogRedaction.sanitize(raw);assertFalse(safe.contains(PASSWORD));assertTrue(safe.contains("REDACTED"));assertFalse(LogRedaction.sanitize("{invalid "+PASSWORD).contains(PASSWORD));
        request("POST","/api/admin/users/resetPassword",superToken,map("userId",a.getId(),"newPassword",PASSWORD));
        assertTrue(logs.findAllByTenantId(1L).stream().anyMatch(l->l.getRequestUrl().endsWith("resetPassword")));
        assertTrue(logs.findAllByTenantId(1L).stream().allMatch(l->l.getRequestParams()==null||!l.getRequestParams().contains(PASSWORD)));
    }
    @Test void passwordChangesAndAdminDisableInvalidateExistingSessions() throws Exception {
        VerifyCode c=code(a,"change_password");
        assertEquals(200,status(request("POST","/api/user/changePassword",ta,map("password",PASSWORD,"confirmPassword",PASSWORD,"verifyCode",c.getCode()))));
        assertEquals(401,status(request("GET","/api/user/assets",ta,null)));
        String userToken=login(a);
        assertEquals(200,status(request("POST","/api/admin/users/resetPassword",superToken,map("userId",a.getId(),"newPassword",PASSWORD))));
        assertEquals(401,status(request("GET","/api/user/assets",userToken,null)));
        assertEquals(200,status(request("PUT","/api/admin/auth/profile/password",superToken,map("oldPassword",PASSWORD,"newPassword",PASSWORD))));
        assertEquals(401,status(request("GET","/api/admin/users",superToken,null)));
        AdminUser admin=admins.findByTenantIdAndAccount(1L, prefix+"super_admin").get();
        LoginRequest login=new LoginRequest();login.setAccount(admin.getAccount());login.setPassword(PASSWORD);
        String fresh=adminAuth.login(login).getToken();
        admin=admins.findByTenantIdAndId(1L, admin.getId()).get();admin.setEnabled(false);admins.saveAndFlush(admin);
        assertEquals(401,status(request("GET","/api/admin/users",fresh,null)));
    }
    @Test void wrongVerificationCodesAreLimitedAndCannotChangePassword() {
        VerifyCode c=code(a,"forget_password");String before=users.findByTenantIdAndId(1L, a.getId()).get().getPasswordHash();
        ResetPasswordRequest req=new ResetPasswordRequest();req.setEmail(a.getEmail());req.setPassword(PASSWORD);req.setConfirmPassword(PASSWORD);req.setVerifyCode("invalid");
        for(int i=0;i<5;i++) assertThrows(BusinessException.class,()->auth.resetPassword(req));
        req.setVerifyCode(c.getCode());assertThrows(BusinessException.class,()->auth.resetPassword(req));
        assertEquals(5,codes.findByTenantIdAndId(1L, c.getId()).get().getFailedAttempts());assertEquals(before,users.findByTenantIdAndId(1L, a.getId()).get().getPasswordHash());
    }
    @Test void financialPenaltyAndAgentYieldsRejectForeignOrders()throws Exception {
        FinancialOrder o=new FinancialOrder();o.setUserId(a.getId());o.setProductId(1L);o.setProductName("QA");o.setPurchaseAmount(new BigDecimal("100"));o.setDailyYieldRate(BigDecimal.ONE);o.setDailyYield(BigDecimal.ONE);o.setTotalYield(BigDecimal.TEN);o.setTermDays(10);o=financialOrders.saveAndFlush(o);
        assertEquals(400,status(request("GET","/api/financial/penalty/"+o.getId(),tb,null)));
        assertEquals(200,status(request("GET","/api/financial/penalty/"+o.getId(),ta,null)));
        UserAccount agent=user("financeAgent","agent",null);grant(agent,"financial_orders");String token=agentLogin(agent);
        assertEquals(403,status(request("GET","/api/admin/financial/yield/order/"+o.getId(),token,null)));
        JsonNode list=body(request("GET","/api/admin/financial/orders",token,null)).path("list");assertEquals(0,list.size());
        assertEquals(a.getId(),financialOrders.findByTenantIdAndId(1L, o.getId()).get().getUserId());
    }
    void approveAsAdmin(Long id) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(jwt.parse(superToken).getSubject().substring(6),null,
                Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        try { depositReview.approveDeposit(id); } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    DepositRecord deposit(UserAccount u,int amount){DepositRecord d=new DepositRecord();d.setUserId(u.getId());d.setType("digital");d.setNetwork("USDT-TRC20");d.setAddress("test");d.setAmount(new BigDecimal(amount));d.setStatus("PENDING");return deposits.saveAndFlush(d);}
    @Test void repeatedApprovalAndMixedTransferCompetitionNeverDoubleCredit()throws Exception {
        DepositRecord d=deposit(a,10);BigDecimal start=balance(a,"FUND");
        List<Boolean> outcomes=race(()->{approveAsAdmin(d.getId());return true;},()->{approveAsAdmin(d.getId());return true;});
        assertEquals(1,outcomes.stream().filter(Boolean.TRUE::equals).count());same(start.add(BigDecimal.TEN),balance(a,"FUND"));assertEquals("COMPLETED",deposits.findByTenantIdAndId(1L, d.getId()).get().getStatus());
        DepositRecord mixed=deposit(a,11);BigDecimal initial=balance(a,"FUND"),target=balance(a,"CONTRACT");
        outcomes=race(()->{approveAsAdmin(mixed.getId());return true;},()->transfer("FUND","CONTRACT",7,"mixed"));
        BigDecimal expected=initial.add(outcomes.get(0)?new BigDecimal("11"):BigDecimal.ZERO).subtract(outcomes.get(1)?new BigDecimal("7"):BigDecimal.ZERO);
        same(expected,balance(a,"FUND"));same(target.add(outcomes.get(1)?new BigDecimal("7"):BigDecimal.ZERO),balance(a,"CONTRACT"));
        assertEquals(outcomes.get(0)?"COMPLETED":"PENDING",deposits.findByTenantIdAndId(1L, mixed.getId()).get().getStatus());
        outcomes=race(()->transfer("FUND","OPTION",6000,null),()->transfer("FUND","OPTION",6000,null));assertEquals(1,outcomes.stream().filter(Boolean.TRUE::equals).count());assertTrue(balance(a,"FUND").signum()>=0);
    }
    @Test void cancelCompetitionUnfreezesMarginExactlyOnce()throws Exception {
        TradingSymbol symbol=symbol();CreateContractOrderRequest req=new CreateContractOrderRequest();req.setSymbol(symbol.getSymbol());req.setSide("BUY");req.setType("LIMIT");req.setQuantity(BigDecimal.ONE);req.setPrice(new BigDecimal("100"));
        BigDecimal before=balance(a,"CONTRACT");ContractOrder order=contractService.createOrder(a.getId(),req);
        List<Boolean> outcomes=race(()->{contractService.cancelOrder(a.getId(),order.getId());return true;},()->{contractService.adminCancelOrder(order.getId());return true;});
        assertEquals(1,outcomes.stream().filter(Boolean.TRUE::equals).count());same(before,balance(a,"CONTRACT"));same(BigDecimal.ZERO,assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(),"CONTRACT").get().getFrozen());assertEquals("CANCELLED",contracts.findByTenantIdAndId(1L, order.getId()).get().getStatus());
    }

    @Test void forgedContractPricesCannotCreateProfitAndOldClientsStillWork() throws Exception {
        identity(b,"APPROVED"); // Ownership/forged-price fixture gets past KYC; unverified exit denial remains separately tested under D01-A.
        TradingSymbol s=symbol();s.setLeverage(new BigDecimal("100"));symbols.saveAndFlush(s);
        for(String side:Arrays.asList("BUY","SELL")) {
            quote(s,"105");
            MvcResult created=request("POST","/api/trade/contract/order",ta,map("symbol",s.getSymbol(),"side",side,"type","MARKET","quantity","0.01","currentPrice",1));
            assertEquals(200,status(created));long id=body(created).path("orderId").asLong();
            same(new BigDecimal("105"),contracts.findByTenantIdAndId(1L, id).get().getOpenPrice());
            assertEquals(400,status(request("POST","/api/trade/contract/order/"+id+"/close",tb,map("closePrice",999999))));
            quote(s,"106");assertEquals(200,status(request("POST","/api/trade/contract/order/"+id+"/close",ta,map("closePrice",999999))));
            ContractOrder closed=contracts.findByTenantIdAndId(1L, id).get();same(new BigDecimal("106"),closed.getClosePrice());same(new BigDecimal("BUY".equals(side)?"10":"-10"),closed.getProfit());
            BigDecimal after=balance(a,"CONTRACT");assertEquals(400,status(request("POST","/api/trade/contract/order/"+id+"/close",ta,null)));same(after,balance(a,"CONTRACT"));
        }
        for(Object payload:Arrays.asList(null,map(),map("closePrice","invalid"),map("closePrice",-1))) {
            MvcResult created=request("POST","/api/trade/contract/order",ta,map("symbol",s.getSymbol(),"side","BUY","type","MARKET","quantity","0.01"));
            assertEquals(200,status(created));long id=body(created).path("orderId").asLong();
            assertEquals(200,status(request("POST","/api/trade/contract/order/"+id+"/close",ta,payload)));same(BigDecimal.ZERO,contracts.findByTenantIdAndId(1L, id).get().getProfit());
        }
        same(new BigDecimal("9998.2"),balance(a,"CONTRACT"));same(BigDecimal.ZERO,assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(),"CONTRACT").get().getFrozen());
    }
    @Test void missingQuotesRejectContractExecutionWithoutChangingOrdersOrFunds() throws Exception {
        TradingSymbol s=symbol();Map<String,Object> input=map("symbol",s.getSymbol(),"side","BUY","type","MARKET","quantity","0.01","currentPrice",1);
        for(String price:Arrays.asList(null,"0","-1")) {
            quote(s,price);assertEquals(400,status(request("POST","/api/trade/contract/order",ta,input)));
            assertTrue(contracts.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, a.getId()).isEmpty());same(new BigDecimal("10000"),balance(a,"CONTRACT"));same(BigDecimal.ZERO,assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(),"CONTRACT").get().getFrozen());
        }
        quote(s,"105");MvcResult created=request("POST","/api/trade/contract/order",ta,input);assertEquals(200,status(created));long id=body(created).path("orderId").asLong();
        AssetAccount before=assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(),"CONTRACT").get();
        for(String price:Arrays.asList(null,"0","-1")) {
            quote(s,price);assertEquals(400,status(request("POST","/api/trade/contract/order/"+id+"/close",ta,map("closePrice",999999))));
            assertThrows(BusinessException.class,()->contractService.adminCloseOrder(id,new BigDecimal("999999")));
            ContractOrder order=contracts.findByTenantIdAndId(1L, id).get();assertEquals("OPEN",order.getStatus());assertNull(order.getCloseTime());assertNull(order.getClosePrice());
            AssetAccount after=assets.findByTenantIdAndId(1L, before.getId()).get();same(before.getAvailable(),after.getAvailable());same(before.getFrozen(),after.getFrozen());
        }
    }
    @Test void adminAndStopTriggersCannotSubstituteTheirOwnExecutionPrices() throws Exception {
        TradingSymbol s=symbol();quote(s,"105");
        for(String side:Arrays.asList("BUY","SELL")) {
            MvcResult created=request("POST","/api/trade/contract/order",ta,map("symbol",s.getSymbol(),"side",side,"type","MARKET","quantity","0.01","stopLoss","BUY".equals(side)?999999:1));
            assertEquals(200,status(created));long id=body(created).path("orderId").asLong();contractService.checkAndAutoCloseOrders(s.getSymbol(),new BigDecimal("999999"));
            ContractOrder closed=contracts.findByTenantIdAndId(1L, id).get();assertEquals("CLOSED",closed.getStatus());same(new BigDecimal("105"),closed.getClosePrice());same(BigDecimal.ZERO,closed.getProfit());
        }
        MvcResult created=request("POST","/api/trade/contract/order",ta,map("symbol",s.getSymbol(),"side","BUY","type","MARKET","quantity","0.01"));
        assertEquals(200,status(created));long id=body(created).path("orderId").asLong();
        BigDecimal before=balance(a,"CONTRACT");
        assertEquals(400,status(request("POST","/api/admin/orders/contract/"+id+"/close",superToken,map("closePrice",999999))));same(before,balance(a,"CONTRACT"));assertEquals("OPEN",contracts.findByTenantIdAndId(1L,id).get().getStatus());
        String requestId=UUID.randomUUID().toString();Map<String,Object> command=map("reason","Fixture controlled exit at server quote","requestId",requestId);
        assertEquals(200,status(request("POST","/api/admin/orders/contract/"+id+"/close",superToken,command)));assertEquals(200,status(request("POST","/api/admin/orders/contract/"+id+"/close",superToken,command)));
        same(new BigDecimal("105"),contracts.findByTenantIdAndId(1L, id).get().getClosePrice());same(new BigDecimal("9999.1"),balance(a,"CONTRACT"));
    }
    @Test void optionOpeningIgnoresClientPricesAndRequiresFreshQuotes() throws Exception {
        TradingSymbol s=symbol();durations.findByTenantIdAndDuration(1L, 60).orElseGet(()->{OptionDuration d=new OptionDuration();d.setDuration(60);d.setLabel("60s");d.setSortOrder(1);d.setEnabled(true);d.setProfitRate(new BigDecimal("0.8"));d.setLossRate(BigDecimal.ONE);d.setMinAmount(BigDecimal.ONE);d.setMaxAmount(new BigDecimal("100"));return durations.saveAndFlush(d);});
        Map<String,Object> input=map("symbol",s.getSymbol(),"direction","UP","duration",60,"amount",10,"currentPrice",1);
        quote(s,null);assertEquals(400,status(request("POST","/api/trade/option/order",ta,input)));same(new BigDecimal("10000"),balance(a,"OPTION"));assertTrue(options.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, a.getId()).isEmpty());
        quote(s,"105");for(Object clientPrice:Arrays.asList(1,999999,-1,null)) {input.put("currentPrice",clientPrice);assertEquals(200,status(request("POST","/api/trade/option/order",ta,input)));}
        List<OptionOrder> opened=options.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, a.getId());assertEquals(4,opened.size());for(OptionOrder o:opened)same(new BigDecimal("105"),o.getOpenPrice());
        same(new BigDecimal("9960"),balance(a,"OPTION"));same(new BigDecimal("40"),assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(),"OPTION").get().getFrozen());
    }

    ContractOrder limit(TradingSymbol symbol, String side, String price) {
        CreateContractOrderRequest request=new CreateContractOrderRequest();request.setSymbol(symbol.getSymbol());request.setSide(side);
        request.setType("LIMIT");request.setQuantity(new BigDecimal("0.01"));request.setPrice(new BigDecimal(price));
        return contractService.createOrder(a.getId(),request);
    }
    void quote(TradingSymbol symbol, String value) {
        org.mockito.Mockito.when(quotes.freshPrice(symbol.getSymbol())).thenReturn(value==null?null:new BigDecimal(value));
    }
    @Test void limitOrdersUseFreshPriceWithinLimitWithoutFreezingAgain() {
        TradingSymbol s=symbol();ContractOrder buy=limit(s,"BUY","99"),sell=limit(s,"SELL","101");
        AssetAccount frozen=assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(),"CONTRACT").get();
        quote(s,"100");assertEquals(0,contractService.matchPendingLimitOrders());assertNull(contracts.findByTenantIdAndId(1L, buy.getId()).get().getOpenTime());
        quote(s,"99");assertEquals(1,contractService.matchPendingLimitOrders());
        ContractOrder filled=contracts.findByTenantIdAndId(1L, buy.getId()).get();assertEquals("OPEN",filled.getStatus());same(new BigDecimal("99"),filled.getOpenPrice());assertNotNull(filled.getOpenTime());
        assertEquals("PENDING",contracts.findByTenantIdAndId(1L, sell.getId()).get().getStatus());
        assertEquals(0,contractService.matchPendingLimitOrders());assertEquals(filled.getOpenTime(),contracts.findByTenantIdAndId(1L, buy.getId()).get().getOpenTime());
        quote(s,"101");assertEquals(1,contractService.matchPendingLimitOrders());same(new BigDecimal("101"),contracts.findByTenantIdAndId(1L, sell.getId()).get().getOpenPrice());
        AssetAccount after=assets.findByTenantIdAndId(1L, frozen.getId()).get();same(frozen.getAvailable(),after.getAvailable());same(frozen.getFrozen(),after.getFrozen());
        ContractOrder gapBuy=limit(s,"BUY","105"),gapSell=limit(s,"SELL","95");
        quote(s,"100");assertEquals(2,contractService.matchPendingLimitOrders());
        for(ContractOrder original:Arrays.asList(gapBuy,gapSell)) {
            ContractOrder current=contracts.findByTenantIdAndId(1L, original.getId()).get();same(original.getPrice(),current.getPrice());same(new BigDecimal("100"),current.getOpenPrice());same(new BigDecimal("100"),current.getCurrentPrice());
            same(new BigDecimal("10"),current.getMargin());same(original.getFee(),current.getFee());same(original.getLeverage(),current.getLeverage());assertEquals(1,current.getRowVersion());
        }
    }
    @Test void limitOrdersWaitForFreshQuotesAndNeverBackfillHistoricalOrdersOrRepriceMarkets() {
        TradingSymbol s=symbol();ContractOrder current=limit(s,"BUY","100"),historical=limit(s,"SELL","100");
        historical.setLimitMatchEnabled(false);historical=contracts.saveAndFlush(historical);long version=historical.getRowVersion();
        quote(s,null);assertEquals(0,contractService.matchPendingLimitOrders());
        quote(s,"0");assertEquals(0,contractService.matchPendingLimitOrders());
        quote(s,"-1");assertEquals(0,contractService.matchPendingLimitOrders());
        assertEquals("PENDING",contracts.findByTenantIdAndId(1L, current.getId()).get().getStatus());
        quote(s,"100");assertEquals(1,contractService.matchPendingLimitOrders());
        historical=contracts.findByTenantIdAndId(1L, historical.getId()).get();assertEquals("PENDING",historical.getStatus());assertNull(historical.getOpenTime());assertEquals(version,historical.getRowVersion());
        CreateContractOrderRequest request=new CreateContractOrderRequest();request.setSymbol(s.getSymbol());request.setSide("BUY");request.setType("MARKET");request.setQuantity(new BigDecimal("0.01"));request.setCurrentPrice(new BigDecimal("37"));
        ContractOrder market=contractService.createOrder(a.getId(),request);assertEquals(0,contractService.matchPendingLimitOrders());same(new BigDecimal("100"),contracts.findByTenantIdAndId(1L, market.getId()).get().getOpenPrice());
    }
    @Test void selectedLeverageUsesPositionValueAndChargesFeeOnce() throws Exception {
        TradingSymbol s = symbol();
        for (Object leverage : Arrays.asList(null, 1, 20, 100)) {
            for (String side : Arrays.asList("BUY", "SELL")) {
                quote(s, "100");
                BigDecimal before = balance(a, "CONTRACT");
                Map<String, Object> input = map("symbol", s.getSymbol(), "type", "MARKET", "side", side,
                        "quantity", "0.01", "leverage", leverage, "currentPrice", 1);
                MvcResult result = request("POST", "/api/trade/contract/order", ta, input);
                assertEquals(200, status(result));
                ContractOrder order = contracts.findByTenantIdAndId(1L, body(result).path("orderId").asLong()).get();
                BigDecimal selected = new BigDecimal(leverage == null ? "100" : leverage.toString());
                same(selected, order.getLeverage()); same(new BigDecimal("1000"), order.getLotSize());
                BigDecimal margin = new BigDecimal("1000").divide(selected);
                same(margin, order.getMargin()); same(new BigDecimal("0.3"), order.getFee());
                same(before.subtract(margin).subtract(order.getFee()), balance(a, "CONTRACT"));
                same(margin.add(order.getFee()), assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(), "CONTRACT").get().getFrozen());
                quote(s, "101");
                ContractOrder closed = "BUY".equals(side) ? contractService.closeOrder(a.getId(), order.getId(), BigDecimal.ONE)
                        : contractService.adminCloseOrder(order.getId(), BigDecimal.ONE);
                BigDecimal profit = new BigDecimal("BUY".equals(side) ? "10" : "-10");
                same(profit, closed.getProfit()); same(before.add(profit).subtract(order.getFee()), balance(a, "CONTRACT"));
                same(BigDecimal.ZERO, assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(), "CONTRACT").get().getFrozen());
            }
        }
    }

    @Test void invalidLeverageAndInsufficientMarginNeverMoveFunds() throws Exception {
        TradingSymbol s = symbol(); quote(s, "100");
        for (Object leverage : Arrays.asList(0, -1, "0.5", "2.5", 101, "1e100", "NaN")) {
            assertEquals(400, status(request("POST", "/api/trade/contract/order", ta,
                    map("symbol", s.getSymbol(), "type", "MARKET", "side", "BUY", "quantity", "0.01", "leverage", leverage))));
        }
        assertEquals(400, status(request("POST", "/api/trade/contract/order", ta,
                map("symbol", s.getSymbol(), "type", "MARKET", "side", "BUY", "quantity", "100", "leverage", 1))));
        s.setMaxLeverage(new BigDecimal("20")); symbols.saveAndFlush(s);
        assertEquals(400, status(request("POST", "/api/trade/contract/order", ta,
                map("symbol", s.getSymbol(), "type", "MARKET", "side", "BUY", "quantity", "0.01", "leverage", 100))));
        assertTrue(contracts.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, a.getId()).isEmpty());
        same(new BigDecimal("10000"), balance(a, "CONTRACT"));
        same(BigDecimal.ZERO, assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(), "CONTRACT").get().getFrozen());
        ContractOrder pending = limit(s, "BUY", "100"); same(new BigDecimal("20"), pending.getLeverage());
        same(new BigDecimal("50"), pending.getMargin());
        contractService.cancelOrder(a.getId(), pending.getId()); same(new BigDecimal("10000"), balance(a, "CONTRACT"));
    }

    @Test void orderSnapshotsPreserveLegacySettlementAndNewPositionSize() {
        TradingSymbol s = symbol(); quote(s, "100");
        ContractOrder legacy = limit(s, "BUY", "100");
        legacy.setLotSize(null); legacy.setLeverage(new BigDecimal("10"));
        legacy.setStatus("OPEN"); legacy.setOpenPrice(new BigDecimal("100")); contracts.saveAndFlush(legacy);
        ContractOrder current = limit(s, "BUY", "100");
        s.setLotSize(new BigDecimal("2000")); s.setLeverage(new BigDecimal("50")); s.setMaxLeverage(BigDecimal.ONE); symbols.saveAndFlush(s);
        assertEquals(1, contractService.matchPendingLimitOrders());
        same(new BigDecimal("1000"), contracts.findByTenantIdAndId(1L, current.getId()).get().getLotSize());
        quote(s, "101");
        same(new BigDecimal("0.1"), contractService.closeOrder(a.getId(), legacy.getId(), null).getProfit());
        same(new BigDecimal("10"), contractService.closeOrder(a.getId(), current.getId(), null).getProfit());
        same(new BigDecimal("10009.8"), balance(a, "CONTRACT"));
        same(BigDecimal.ZERO, assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(), "CONTRACT").get().getFrozen());
    }

    @Test void limitFillAdjustsMarginAndWaitsIfAdditionalFundsAreUnavailable() {
        TradingSymbol s = symbol();
        ContractOrder buy = limit(s, "BUY", "105");
        AssetAccount before = assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(), "CONTRACT").get();
        quote(s, "100"); assertEquals(1, contractService.matchPendingLimitOrders());
        same(new BigDecimal("10"), contracts.findByTenantIdAndId(1L, buy.getId()).get().getMargin());
        AssetAccount after = assets.findByTenantIdAndId(1L, before.getId()).get();
        same(before.getAvailable().add(new BigDecimal("0.5")), after.getAvailable());
        same(before.getFrozen().subtract(new BigDecimal("0.5")), after.getFrozen());
        ContractOrder sell = limit(s, "SELL", "95");
        after = assets.findByTenantIdAndId(1L, before.getId()).get(); after.setAvailable(BigDecimal.ZERO); assets.saveAndFlush(after);
        BigDecimal frozen = after.getFrozen();
        assertEquals(0, contractService.matchPendingLimitOrders());
        assertEquals("PENDING", contracts.findByTenantIdAndId(1L, sell.getId()).get().getStatus());
        same(frozen, assets.findByTenantIdAndId(1L, before.getId()).get().getFrozen());
        after = assets.findByTenantIdAndId(1L, before.getId()).get(); after.setAvailable(new BigDecimal("0.5")); assets.saveAndFlush(after);
        assertEquals(1, contractService.matchPendingLimitOrders());
        same(new BigDecimal("10"), contracts.findByTenantIdAndId(1L, sell.getId()).get().getMargin());
        same(BigDecimal.ZERO, balance(a, "CONTRACT"));
        same(frozen.add(new BigDecimal("0.5")), assets.findByTenantIdAndId(1L, before.getId()).get().getFrozen());
    }

    @Test void marginRoundsUpAndZeroFeeStaysZero() {
        TradingSymbol s = symbol(); s.setLotSize(BigDecimal.ONE); s.setFeeMultiplier(BigDecimal.ZERO); symbols.saveAndFlush(s);
        CreateContractOrderRequest request = new CreateContractOrderRequest(); request.setSymbol(s.getSymbol());
        request.setType("LIMIT"); request.setSide("BUY"); request.setQuantity(new BigDecimal("0.01"));
        request.setPrice(BigDecimal.ONE); request.setLeverage(new BigDecimal("3"));
        ContractOrder order = contractService.createOrder(a.getId(), request);
        same(new BigDecimal("0.0033333333333334"), order.getMargin()); same(BigDecimal.ZERO, order.getFee());
        contractService.adminCancelOrder(order.getId()); same(new BigDecimal("10000"), balance(a, "CONTRACT"));
    }

    @Test void liquidationUsesPositionEquityAndPreservesPendingFrozenFunds() {
        TradingSymbol s = symbol(); quote(s, "100");
        AssetAccount account = assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(), "CONTRACT").get();
        account.setAvailable(new BigDecimal("20.6")); assets.saveAndFlush(account);
        ContractOrder opened = limit(s, "BUY", "100"); assertEquals(1, contractService.matchPendingLimitOrders());
        ContractOrder pending = limit(s, "BUY", "90");
        same(BigDecimal.ONE, balance(a, "CONTRACT"));
        quote(s, "98.9"); org.mockito.Mockito.when(quotes.freshPrices()).thenReturn(new HashMap<>());
        contractService.checkAndForceCloseOrders(null);
        assertEquals("CLOSED", contracts.findByTenantIdAndId(1L, opened.getId()).get().getStatus());
        assertEquals("PENDING", contracts.findByTenantIdAndId(1L, pending.getId()).get().getStatus());
        same(new BigDecimal("-11"), contracts.findByTenantIdAndId(1L, opened.getId()).get().getProfit());
        same(BigDecimal.ZERO, balance(a, "CONTRACT"));
        same(new BigDecimal("9.3"), assets.findByTenantIdAndId(1L, account.getId()).get().getFrozen());
        contractService.cancelOrder(a.getId(), pending.getId()); same(new BigDecimal("9.3"), balance(a, "CONTRACT"));
    }

    @Test void timedControlRequiresItsMenuAndValidatesInputs() throws Exception {
        String base = "/api/admin/ai-control/1";
        Map<String, Object> valid = map("durationSeconds", 10, "targetPrice", 100, "intensity", 10, "randomOscillation", true);
        assertEquals(401, status(request("POST", base + "/start", null, valid)));
        assertEquals(401, status(request("POST", base + "/start", ta, valid)));
        UserAccount agent = user("controlAgent", "agent", null); String token = agentLogin(agent);
        assertEquals(403, status(request("GET", base, token, null)));
        assertEquals(403, status(request("POST", base + "/preview", token, valid)));
        grant(agent, "ai_control", "preview", "start", "restore", "manual");
        org.mockito.Mockito.when(quotes.controlStatus(1L)).thenReturn(map("id", 1));
        org.mockito.Mockito.when(quotes.previewControl(1L, 10, new BigDecimal("100"), 10, true)).thenReturn(map("feasible", true));
        assertEquals(200, status(request("GET", base, token, null)));
        assertEquals(200, status(request("POST", base + "/preview", token, valid)));
        assertEquals(200, status(request("POST", base + "/start", token, valid)));
        org.mockito.Mockito.verify(quotes).startControl(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(10), org.mockito.ArgumentMatchers.eq(new BigDecimal("100")), org.mockito.ArgumentMatchers.eq(10), org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.argThat(o -> Boolean.FALSE.equals(o.getAutoRestore()) && "GRADUAL".equals(o.getRestoreMode()) && o.getRestoreDurationSeconds() == 10 && o.getRestoreIntensity() == 5 && o.getRestoreRandomOscillation() && o.getAutoReplaceHistory()));
        assertEquals(200, status(request("POST", base + "/start", token, map("durationSeconds", 10, "targetPrice", 100, "intensity", 10))));
        org.mockito.Mockito.verify(quotes, org.mockito.Mockito.times(2)).startControl(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(10), org.mockito.ArgumentMatchers.eq(new BigDecimal("100")), org.mockito.ArgumentMatchers.eq(10), org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.argThat(o -> Boolean.FALSE.equals(o.getAutoRestore()) && "GRADUAL".equals(o.getRestoreMode()) && o.getRestoreDurationSeconds() == 10 && o.getRestoreIntensity() == 5 && o.getRestoreRandomOscillation() && o.getAutoReplaceHistory()));
        assertEquals(400, status(request("POST", base + "/start", token, map("durationSeconds", 10, "targetPrice", 100, "intensity", 1, "randomOscillation", null))));
        assertEquals(400, status(request("POST", base + "/start", superToken, map("durationSeconds", 0, "targetPrice", 100, "intensity", 1))));
        assertEquals(400, status(request("POST", base + "/start", superToken, map("durationSeconds", 10, "targetPrice", -1, "intensity", 1))));
        assertEquals(400, status(request("POST", base + "/restore", token, map("durationSeconds", 10, "intensity", 11))));
        assertEquals(400, status(request("POST", base + "/start", token, map("durationSeconds", 1.5, "targetPrice", 100, "intensity", 1))));
        assertEquals(400, status(request("POST", base + "/restore", token, map("durationSeconds", 10, "intensity", 1.5))));
        assertEquals(400, status(request("POST", base + "/manual", token, map("enabled", true))));
        assertEquals(200, status(request("POST", base + "/restore", token, map("durationSeconds", 10, "intensity", 3))));
        org.mockito.Mockito.verify(quotes).restoreControl(1L, 10, 3, false);
        assertEquals(200, status(request("POST", base + "/restore", token, map("durationSeconds", 10, "intensity", 3, "randomOscillation", true))));
        org.mockito.Mockito.verify(quotes).restoreControl(1L, 10, 3, true);
        assertEquals(200, status(request("POST", base + "/manual", token, map("enabled", false, "offset", 0))));
    }

    @Test @SuppressWarnings("unchecked") void timedControlPersistsAndSymbolEditsCannotEraseIt() throws Exception {
        TradingSymbol symbol = symbol(); symbol.setCategory("Metal"); symbol.setSourceCategory("Metal"); symbol.setMarketSource(com.gtcfesk.exchange.market.MarketInstrumentCatalog.inferredSource("Metal")); symbol = symbols.saveAndFlush(symbol);
        ForexQuoteMarketService real = new ForexQuoteMarketService();
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(real, "symbols", symbols);
            org.springframework.test.util.ReflectionTestUtils.setField(real,"v3Enabled",false);
            org.springframework.test.util.ReflectionTestUtils.setField(real, "redis", redis);
            Object tenantMarketState=org.springframework.test.util.ReflectionTestUtils.invokeMethod(real,"state");
            Map<String, Object> groups = (Map<String, Object>) org.springframework.test.util.ReflectionTestUtils.getField(tenantMarketState, "groups");
            Map<String, Map<String, Object>> cache = (Map<String, Map<String, Object>>) org.springframework.test.util.ReflectionTestUtils.getField(groups.get("Metal"), "quotes");
            cache.put(symbol.getSymbol(), map("price", 90d, "timestamp", System.currentTimeMillis(), "fetchedAt", System.currentTimeMillis(), "sourceAvailable", true));
            real.startControl(symbol.getId(), 10, new BigDecimal("100"), 10, true);
            TradingSymbol persisted = symbols.findByTenantIdAndId(1L, symbol.getId()).get();
            assertTrue(PriceControlPath.running(persisted)); same(new BigDecimal("100"), persisted.getControlTargetPrice());
            assertEquals(10, persisted.getControlIntensity()); assertTrue(persisted.getRowVersion() > symbol.getRowVersion());
            assertTrue(persisted.getControlRandomOscillation());
            // The public symbol body omits task fields, and a stale manual offset cannot replace the task.
            persisted.setControlEnabled(false); persisted.setControlPriceOffset(BigDecimal.ZERO);
            assertEquals(200, status(request("POST", "/api/admin/symbols/update", superToken, persisted)));
            TradingSymbol afterEdit = symbols.findByTenantIdAndId(1L, symbol.getId()).get();
            assertTrue(PriceControlPath.running(afterEdit)); assertEquals(persisted.getControlStartedAt(), afterEdit.getControlStartedAt());
            assertFalse(json.valueToTree(afterEdit).has("controlTargetPrice"));
            assertFalse(json.valueToTree(afterEdit).has("controlRandomOscillation"));
            assertTrue(afterEdit.getControlRandomOscillation());
            afterEdit.setControlStartedAt(System.currentTimeMillis() - 5000); afterEdit.setControlIntensity(1); symbols.saveAndFlush(afterEdit);
            TradingSymbol stale = symbols.findByTenantIdAndId(1L, symbol.getId()).get();
            real.restoreControl(symbol.getId(), 10, 3, true);
            TradingSymbol restoring = symbols.findByTenantIdAndId(1L, symbol.getId()).get();
            assertTrue(restoring.getControlRestoring()); assertTrue(PriceControlPath.running(restoring));
            assertTrue(restoring.getControlRandomOscillation());
            stale.setControlPriceOffset(BigDecimal.ONE);
            assertThrows(org.springframework.dao.OptimisticLockingFailureException.class, () -> symbols.saveAndFlush(stale));
            real.manualControl(symbol.getId(), false, BigDecimal.ZERO);
            assertFalse(symbols.findByTenantIdAndId(1L, symbol.getId()).get().getControlEnabled());
            assertEquals(0, new BigDecimal("90").compareTo(real.freshPrice(symbol.getSymbol())));
        } finally { real.stop(); }
    }

    @Test void limitFillRacesWithCancellationAndOtherWorkersOnlyOnce() throws Exception {
        TradingSymbol s=symbol();quote(s,"100");
        for(int i=0;i<10;i++) {
            AssetAccount before=assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(),"CONTRACT").get();ContractOrder order=limit(s,i%2==0?"BUY":"SELL","100");BigDecimal cost=order.getMargin().add(order.getFee());
            final boolean admin=i%2==0;
            List<Boolean> result=race(()->contractService.matchPendingLimitOrders()==1,()->{if(admin)contractService.adminCancelOrder(order.getId());else contractService.cancelOrder(a.getId(),order.getId());return true;});
            assertEquals(1,result.stream().filter(Boolean.TRUE::equals).count());ContractOrder finalOrder=contracts.findByTenantIdAndId(1L, order.getId()).get();AssetAccount after=assets.findByTenantIdAndId(1L, before.getId()).get();
            if("OPEN".equals(finalOrder.getStatus())) {same(before.getAvailable().subtract(cost),after.getAvailable());same(before.getFrozen().add(cost),after.getFrozen());assertNotNull(finalOrder.getOpenTime());}
            else {assertEquals("CANCELLED",finalOrder.getStatus());same(before.getAvailable(),after.getAvailable());same(before.getFrozen(),after.getFrozen());assertNull(finalOrder.getOpenTime());}
            assertEquals(0,contractService.matchPendingLimitOrders());assertEquals(1,contracts.findByTenantIdAndId(1L, order.getId()).get().getRowVersion());
        }
        ContractOrder duplicate=limit(s,"BUY","100");AssetAccount before=assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(),"CONTRACT").get();
        List<Boolean> duplicateResults=race(()->contractService.matchPendingLimitOrders()==1,()->contractService.matchPendingLimitOrders()==1);
        assertEquals(1,duplicateResults.stream().filter(Boolean.TRUE::equals).count());assertEquals(1,contracts.findByTenantIdAndId(1L, duplicate.getId()).get().getRowVersion());
        AssetAccount after=assets.findByTenantIdAndId(1L, before.getId()).get();same(before.getAvailable(),after.getAvailable());same(before.getFrozen(),after.getFrozen());
    }

    @Test void catalogAddIsAtomicDeduplicatedAndPreservesSourceOnManualCategory() throws Exception {
        String first="CAT"+prefix.replace("_", "").toUpperCase(), second=first+"B";
        org.mockito.Mockito.when(catalog.resolve(org.mockito.ArgumentMatchers.eq("binance"),org.mockito.ArgumentMatchers.eq("Crypto"),org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
            String code=call.getArgument(2);TradingSymbol row=new TradingSymbol();row.setSymbol(code);row.setAlltickSymbol(code);
            row.setBaseCurrency("ETH");row.setQuoteCurrency("BTC");row.setName(code);row.setMarketSource("binance");row.setSourceCategory("Crypto");row.setMarketInstrumentKey("binance:Crypto:"+code);return row;
        });
        String endpoint="/api/admin/symbols/catalog/add";
        Map<String,Object> input=map("source","binance","sourceCategory","Crypto","projectCategory","CFD","symbols",Arrays.asList(first));
        assertEquals(401,status(request("POST",endpoint,ta,input)));
        MvcResult added=request("POST",endpoint,superToken,input);assertEquals(200,status(added));assertEquals(1,body(added).path("added").size());
        TradingSymbol saved=symbols.findByTenantIdAndSymbol(1L, first).get();assertEquals("CFD",saved.getCategory());assertEquals("Crypto",saved.getSourceCategory());assertEquals("binance",saved.getMarketSource());
        assertEquals(1,body(request("POST",endpoint,superToken,input)).path("existing").size());
        saved.setCategory("US");assertEquals(200,status(request("POST","/api/admin/symbols/update",superToken,saved)));
        assertEquals("Crypto",symbols.findByTenantIdAndId(1L, saved.getId()).get().getSourceCategory());
        saved.setSourceCategory("US");assertEquals(400,status(request("POST","/api/admin/symbols/update",superToken,saved)));
        input.put("symbols",Arrays.asList(second,first));
        org.mockito.Mockito.when(catalog.resolve("binance","Crypto",first)).thenThrow(new BusinessException("目录不再提供此交易对"));
        assertEquals(400,status(request("POST",endpoint,superToken,input)));assertFalse(symbols.findByTenantIdAndSymbol(1L, second).isPresent());
        assertTrue(status(request("POST","/api/admin/symbols/create",superToken,map("symbol","FAKE")))>=400);
    }


    @Test void depositOrdersApiScopeLegacyEntryAndSafeDto() throws Exception {
        org.mockito.Mockito.when(quotes.requireConversionRate("USD","yahoo")).thenReturn(BigDecimal.ONE);
        String url="/api/admin/deposit/orders";BigDecimal before=balance(a,"FUND");
        Map<String,Object> input=map("userId",a.getId(),"amount","100","remark","internal fixture reason","idempotencyKey",UUID.randomUUID().toString());
        assertEquals(401,status(request("POST",url+"/manual",ta,input)));
        String ordinary=admin("admin");assertEquals(403,status(request("POST",url+"/manual",ordinary,input)));
        MvcResult created=request("POST",url+"/manual",superToken,input);assertEquals(200,status(created));long id=body(created).path("id").asLong();
        assertEquals(200,status(request("POST",url+"/manual",superToken,input)));same(before.add(new BigDecimal("100")),balance(a,"FUND"));
        input.put("amount","101");assertEquals(409,status(request("POST",url+"/manual",superToken,input)));
        JsonNode userRecords=body(request("GET","/api/deposit/records",ta,null)).path("list");JsonNode d=userRecords.get(0);
        assertTrue(d.path("remark").isNull());assertFalse(d.has("createdById"));assertFalse(d.has("reviewRemark"));assertFalse(d.has("credit"));
        String query="?userId="+a.getId()+"&source=ADMIN_MANUAL";
        assertEquals(1,body(request("GET",url+"/list"+query,superToken,null)).path("total").asInt());
        assertEquals("100.0000000000000000",body(request("GET",url+"/summary"+query,superToken,null)).path("creditedUsd").asText());
        assertEquals(200,status(request("GET",url+"/export"+query,superToken,null)));
        assertEquals(400,status(request("GET",url+"/list?source=INVALID",superToken,null)));
        UserAccount agent=user("depositAgent","agent",null);grant(agent,"deposit_orders","view_deposit_orders","detail");String token=agentLogin(agent);
        assertEquals(0,body(request("GET",url+"/list",token,null)).path("total").asInt());
        assertEquals(404,status(request("GET",url+"/"+id,token,null)));
        assertEquals(403,status(request("GET",url+"/export",token,null)));
        assertEquals(403,status(request("GET",url+"/recipient/"+a.getId(),token,null)));
        assertEquals(403,status(request("POST",url+"/manual",token,input)));
        assertEquals(400,status(request("POST","/api/admin/users/updateBalance",superToken,map("userId",a.getId(),"account","FUND","amount","2","remark","missing key"))));
        assertEquals(200,status(request("POST","/api/admin/users/updateBalance",superToken,map("userId",a.getId(),"account","FUND","amount","2","remark","legacy adapter","idempotencyKey",UUID.randomUUID().toString()))));
        same(before.add(new BigDecimal("102")),balance(a,"FUND"));assertEquals(2,deposits.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, a.getId()).size());
        assertEquals(400,status(request("POST","/api/admin/users/updateBalance",superToken,map("userId",a.getId(),"fundBalance",0))));same(before.add(new BigDecimal("102")),balance(a,"FUND"));
        Map<String,Object> adjust=map("userId",a.getId(),"fundBalance",0,"confirm",true,"remark","Reset fixture balance through audited adjustment","idempotencyKey",UUID.randomUUID().toString());
        assertEquals(200,status(request("POST","/api/admin/users/updateBalance",superToken,adjust)));assertEquals(200,status(request("POST","/api/admin/users/updateBalance",superToken,adjust)));same(BigDecimal.ZERO,balance(a,"FUND"));
        assertEquals(2,deposits.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, a.getId()).size());
        for(AssetAccount account:assets.findByTenantIdAndUserId(1L, a.getId())){account.setAvailable(BigDecimal.ZERO);account.setFrozen(BigDecimal.ZERO);assets.saveAndFlush(account);}
        assertTrue(assertThrows(BusinessException.class,()->adminUsers.deleteUser(a.getId())).getMessage().contains("业务历史"));assertTrue(deposits.existsByTenantIdAndId(1L, id));
    }
    @Test void detailReviewCannotBypassOldReviewPermission() throws Exception {
        UserAccount agent=user("depositReviewAgent","agent",null);a=users.findByTenantIdAndId(1L, a.getId()).get();a.setParentUserId(agent.getId());a=users.saveAndFlush(a);
        grant(agent,"deposit_orders","view_deposit_orders","detail");String token=agentLogin(agent);DepositRecord d=deposit(a,10);
        assertEquals(403,status(request("POST","/api/admin/deposit/orders/"+d.getId()+"/approve",token,null)));
        assertEquals("PENDING",deposits.findByTenantIdAndId(1L, d.getId()).get().getStatus());
        grant(agent,"deposit_review","approve_deposit","reject_deposit");
        assertEquals(403,status(request("POST","/api/admin/deposit/orders/"+d.getId()+"/approve",token,null)));
        for (String operation : Arrays.asList("approve_deposit", "reject_deposit")) { UserAction action = new UserAction(); action.setUserId(agent.getId()); action.setMenuId(menu("deposit_orders").getId()); action.setActionCode(operation); actions.saveAndFlush(action); }
        assertEquals(200,status(request("POST","/api/admin/deposit/orders/"+d.getId()+"/approve",token,null)));
        assertEquals(409,status(request("POST","/api/admin/deposit/orders/"+d.getId()+"/reject",token,map("remark","no"))));
    }

    @Test void depositSummarySeparatesManualPurposesUserAndLegacyAndPaginates() throws Exception {
        org.mockito.Mockito.when(quotes.requireConversionRate("USD","yahoo")).thenReturn(BigDecimal.ONE);
        String url="/api/admin/deposit/orders",query="?userId="+a.getId();
        for(String purpose:Arrays.asList("BONUS","ADJUSTMENT","RECEIPT")) {
            Map<String,Object> input=map("userId",a.getId(),"amount","100","manualPurpose",purpose,"type",purpose.equals("RECEIPT")?"bank":"manual","proofImage","/fixture.png","remark","=SUM(1)","idempotencyKey",UUID.randomUUID().toString());
            assertEquals(200,status(request("POST",url+"/manual",superToken,input)));
        }
        assertEquals(200,status(request("POST","/api/deposit/submit",ta,map("type","bank","network","BANK","address","fixture","amount","100"))));
        DepositRecord submitted=deposits.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, a.getId()).get(0);approveAsAdmin(submitted.getId());
        DepositRecord legacy=deposit(a,10);approveAsAdmin(legacy.getId());
        JsonNode totals=body(request("GET",url+"/summary"+query,superToken,null));
        assertEquals("410.0000000000000000",totals.path("creditedUsd").asText());
        assertEquals(5,totals.path("groups").size());
        assertEquals(5,body(request("GET",url+"/list"+query+"&size=2&page=2",superToken,null)).path("total").asInt());
        assertEquals(2,body(request("GET",url+"/list"+query+"&size=2&page=2",superToken,null)).path("list").size());
        String exported=request("GET",url+"/export"+query,superToken,null).getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(exported.startsWith("\ufeff"));assertTrue(exported.contains("'=SUM(1)"));
    }

    @Test void standardFxPersistsAndFillsWithoutRepricingLegacyOrders() {
        TradingSymbol s=symbol(); s.setBaseCurrency("USD");s.setQuoteCurrency("JPY");s=symbols.saveAndFlush(s);
        quote(s,"157.2");
        org.mockito.Mockito.when(quotes.requireContractConversionRate("JPY","yahoo")).thenReturn(new BigDecimal("0.0063"));
        org.mockito.Mockito.when(quotes.fxMarginRate(org.mockito.ArgumentMatchers.eq("USD"),org.mockito.ArgumentMatchers.eq("JPY"),org.mockito.ArgumentMatchers.any(BigDecimal.class))).thenReturn(BigDecimal.ONE);
        CreateContractOrderRequest r=new CreateContractOrderRequest();r.setSymbol(s.getSymbol());r.setType("LIMIT");r.setSide("BUY");r.setQuantity(BigDecimal.ONE);r.setPrice(new BigDecimal("157.2"));r.setLeverage(new BigDecimal("100"));
        ContractOrder legacy=contractService.createOrder(a.getId(),r);
        assertNull(legacy.getFxBaseCurrency());same(new BigDecimal("9.9036"),legacy.getMargin());
        s.setSourceCategory("Forex");FxContractRules.defaults(s);symbols.saveAndFlush(s);
        ContractOrder standard=contractService.createOrder(a.getId(),r);
        standard=contracts.findByTenantIdAndId(1L, standard.getId()).get();
        assertEquals("USD",standard.getFxBaseCurrency());same(new BigDecimal("1000"),standard.getMargin());same(new BigDecimal("7"),standard.getFee());
        quote(s,"157");assertEquals(2,contractService.matchPendingLimitOrders());
        standard=contracts.findByTenantIdAndId(1L, standard.getId()).get();legacy=contracts.findByTenantIdAndId(1L, legacy.getId()).get();
        assertEquals("OPEN",standard.getStatus());same(new BigDecimal("1000"),standard.getMargin());
        same(new BigDecimal("1000"),legacy.getLotSize());same(new BigDecimal("9.891"),legacy.getMargin());same(new BigDecimal("30"),legacy.getFee());
        contractService.closeOrder(a.getId(),standard.getId(),null);contractService.closeOrder(a.getId(),legacy.getId(),null);
        AssetAccount account=assets.findByTenantIdAndUserIdAndCoin(1L, a.getId(),"CONTRACT").get();
        same(new BigDecimal("9963"),account.getAvailable());same(BigDecimal.ZERO,account.getFrozen());
    }
}
