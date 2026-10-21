package com.gtcfesk.exchange.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gtcfesk.exchange.common.UploadStorage;
import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.control.BackendLoginRegistry;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.*;
import com.gtcfesk.exchange.user.BalanceAdjustmentService;
import com.gtcfesk.exchange.user.KycIdentityService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JPA transactions prove profile, identity, balances and receipts commit or roll back together. */
@DataJpaTest(properties={"spring.datasource.url=jdbc:h2:mem:customer-profile;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false","logging.level.root=ERROR"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=AdminCustomerProfileTest.Config.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class AdminCustomerProfileTest {
    @Configuration @AutoConfigurationPackage(basePackages="com.gtcfesk.exchange.entity")
    @EntityScan(basePackageClasses=UserAccount.class) static class Config {}
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired javax.sql.DataSource dataSource;
    private UserAccountRepository users;
    private AssetAccountRepository assets;
    private KycRecordRepository kycs;
    private BalanceAdjustmentRepository receipts;
    private AdminPermissionService permissions;
    private BackendAccess access;
    private ControlAuditService audit;
    private KycIdentityService identity;
    private AdminCustomerProfileService service;
    private TransactionTemplate transaction;
    private JdbcTemplate jdbc;
    @TempDir java.nio.file.Path uploadDirectory;
    private UploadStorage uploads;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(4);

    @BeforeEach void setup() {
        TenantContext.clear();TenantContext.open(41L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1","",Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        transaction=new TransactionTemplate(transactionManager);jdbc=new JdbcTemplate(dataSource);
        users=repository(UserAccountRepository.class,UserAccount.class);
        assets=repository(AssetAccountRepository.class,AssetAccount.class);
        kycs=repository(KycRecordRepository.class,KycRecord.class);
        receipts=repository(BalanceAdjustmentRepository.class,BalanceAdjustment.class);
        jdbc.update("DELETE FROM balance_adjustment");jdbc.update("DELETE FROM kyc_record");jdbc.update("DELETE FROM asset_account");jdbc.update("DELETE FROM user_account");
        jdbc.update("INSERT INTO user_account(tenant_id,id,row_version,email,password_hash,status,user_type,kyc_status,kyc_level,created_at,updated_at) VALUES (41,7001,0,'customer@example.com','hidden-hash','normal','normal','NOT_VERIFIED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO user_account(tenant_id,id,row_version,email,password_hash,status,user_type,kyc_status,kyc_level,created_at,updated_at) VALUES (42,7002,0,'other@example.com','hidden-hash','normal','normal','NOT_VERIFIED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        transaction.execute(status -> { AssetAccount asset=new AssetAccount();asset.setUserId(7001L);asset.setCoin("FUND");asset.setAvailable(new BigDecimal("100"));asset.setFrozen(new BigDecimal("25"));assets.saveAndFlush(asset);return null; });
        permissions=mock(AdminPermissionService.class);when(permissions.can(anyString(),anyString())).thenReturn(true);when(permissions.isSuper()).thenReturn(true);
        access=mock(BackendAccess.class);audit=mock(ControlAuditService.class);identity=new KycIdentityService(kycs);
        ReflectionTestUtils.setField(identity,"em",em);
        BalanceAdjustmentService adjustments=new BalanceAdjustmentService(users,assets,receipts,audit);
        uploads=new UploadStorage(uploadDirectory.toString());
        service=new AdminCustomerProfileService(users,assets,kycs,identity,permissions,access,mock(AdminUserService.class),adjustments,passwords,json,audit,uploads,mock(BackendLoginRegistry.class),receipts);
        ReflectionTestUtils.setField(service,"em",em);
    }

    private <R extends org.springframework.data.repository.Repository<E,Long>,E extends TenantOwnedEntity> R repository(Class<R> type,Class<E> entity) {
        TenantRepositoryFactoryBean<R,E,Long> factory=new TenantRepositoryFactoryBean<>(type);factory.setEntityManager(em);factory.afterPropertiesSet();return factory.getObject();
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();TenantContext.clear();}
    private ObjectNode input(){ObjectNode node=json.createObjectNode();node.put("rowVersion",jdbc.queryForObject("SELECT row_version FROM user_account WHERE id=7001",Long.class));node.put("idempotencyKey",UUID.randomUUID().toString());return node;}
    private Map<String,Object> save(ObjectNode input){return transaction.execute(status -> service.save(7001L,input));}
    private void balance(ObjectNode input,String target){input.putObject("balances").put("FUND",target);input.putObject("expectedBalances").put("FUND","100");input.put("reason","customer correction");}

    @Test void savesProfileIdentityPasswordAndBalancesInOneTransactionAndReplays() throws Exception {
        ObjectNode input=input();input.putObject("profile").put("nickname","New name").put("phone","123456").put("remark","");input.put("newPassword","new-secret-123");
        input.putObject("kyc").put("status","APPROVED").put("realName","Customer name").put("idNumber","ID-123");balance(input,"135.75");
        Map<String,Object> result=save(input);
        assertEquals("New name",jdbc.queryForObject("SELECT nickname FROM user_account WHERE id=7001",String.class));
        assertEquals("VERIFIED",jdbc.queryForObject("SELECT kyc_status FROM user_account WHERE id=7001",String.class));
        assertTrue(passwords.matches("new-secret-123",jdbc.queryForObject("SELECT password_hash FROM user_account WHERE id=7001",String.class)));
        assertEquals(0,new BigDecimal("135.75").compareTo(jdbc.queryForObject("SELECT available FROM asset_account WHERE user_id=7001",BigDecimal.class)));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM balance_adjustment",Integer.class));
        String serialized=json.writeValueAsString(result);
        assertFalse(serialized.contains("passwordHash"));assertFalse(serialized.contains("new-secret-123"));assertFalse(serialized.contains("currentToken"));
        assertEquals(0,new BigDecimal("160.75").compareTo(new BigDecimal((String)((Map<?,?>)result.get("data")).get("totalBalance"))));
        save(input); // Same committed request succeeds despite the now-stale rowVersion.
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM balance_adjustment",Integer.class));
        input.with("profile").put("nickname","Different payload");
        assertThrows(ResponseStatusException.class,()->save(input));
        assertEquals("New name",jdbc.queryForObject("SELECT nickname FROM user_account WHERE id=7001",String.class));
    }

    @Test void staleBalanceRollsBackProfileAndCreatesNoReceipt() {
        ObjectNode input=input();input.putObject("profile").put("nickname","Should roll back");balance(input,"200");input.with("expectedBalances").put("FUND","99");
        assertThrows(ResponseStatusException.class,()->save(input));
        assertNull(jdbc.queryForObject("SELECT nickname FROM user_account WHERE id=7001",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM balance_adjustment",Integer.class));
    }

    @Test void auditFailureRollsBackMoneyIdentityAndProfile() {
        ObjectNode input=input();input.putObject("profile").put("nickname","Should roll back");balance(input,"200");
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordCurrent(eq("CUSTOMER_PROFILE_UPDATE"),anyString(),anyString(),any());
        assertThrows(IllegalStateException.class,()->save(input));
        assertNull(jdbc.queryForObject("SELECT nickname FROM user_account WHERE id=7001",String.class));
        assertEquals(0,new BigDecimal("100").compareTo(jdbc.queryForObject("SELECT available FROM asset_account WHERE user_id=7001",BigDecimal.class)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM balance_adjustment",Integer.class));
    }

    @Test void zeroBalanceEditDoesNotCreateMoneyMovement() {
        ObjectNode input=input();balance(input,"100.000");save(input);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM balance_adjustment",Integer.class));
        assertEquals("PROFILE_SAVE",jdbc.queryForObject("SELECT changes FROM balance_adjustment",String.class));
    }

    @Test void rejectsStaleVersionAndUnlistedFields() {
        ObjectNode input=input();input.put("rowVersion",999);input.putObject("profile").put("nickname","stale");
        assertThrows(ResponseStatusException.class,()->save(input));
        ObjectNode forbidden=input();forbidden.putObject("profile").put("passwordHash","injected");
        assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->save(forbidden));
        assertEquals("hidden-hash",jdbc.queryForObject("SELECT password_hash FROM user_account WHERE id=7001",String.class));
    }

    @Test void scopedLookupNeverReturnsOtherTenant() {
        assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->transaction.execute(status -> service.get(7002L)));
        ObjectNode input=input();input.putObject("profile").put("nickname","cross tenant");
        assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->transaction.execute(status -> service.save(7002L,input)));
        assertNull(jdbc.queryForObject("SELECT nickname FROM user_account WHERE id=7002",String.class));
    }

    @Test void checksAgentScopeAndActionBeforeMutation() {
        doThrow(new AccessDeniedException("not subordinate")).when(access).checkUser(7001L);
        ObjectNode input=input();input.putObject("profile").put("nickname","denied");
        assertThrows(AccessDeniedException.class,()->save(input));
        reset(access);doThrow(new AccessDeniedException("permission revoked")).when(permissions).require("users","edit_profile");
        assertThrows(AccessDeniedException.class,()->save(input));
        assertNull(jdbc.queryForObject("SELECT nickname FROM user_account WHERE id=7001",String.class));
    }

    @Test void bindsUploadedPhotosToCustomerAndRejectsOtherOwners() throws Exception {
        java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        org.springframework.mock.web.MockMultipartFile file=new org.springframework.mock.web.MockMultipartFile("file","photo.png","image/png",bytes.toByteArray());
        String uploaded="/api/uploads/images/"+uploads.saveImage(file);
        ObjectNode input=input();input.putObject("profile").put("avatarUrl",uploaded);
        input.putObject("kyc").put("status","APPROVED").put("realName","Customer").put("idNumber","ID-PHOTO").put("idFrontImage",uploaded);
        save(input);
        String avatar=jdbc.queryForObject("SELECT avatar_url FROM user_account WHERE id=7001",String.class);
        String front=jdbc.queryForObject("SELECT id_front_image FROM kyc_record WHERE user_id=7001",String.class);
        assertTrue(avatar.startsWith("/api/uploads/images/41/user/7001/"));assertTrue(front.startsWith("/api/uploads/images/41/user/7001/"));
        assertTrue(java.nio.file.Files.exists(uploadDirectory.resolve("images").resolve(avatar.substring("/api/uploads/images/".length()))));
        ObjectNode forbidden=input();forbidden.putObject("profile").put("avatarUrl","/api/uploads/images/41/user/7002/stolen.png");
        assertThrows(AccessDeniedException.class,()->save(forbidden));
        ObjectNode foreignTenant=input();foreignTenant.putObject("profile").put("avatarUrl","/api/uploads/images/42/staff/1/stolen.png");
        assertThrows(AccessDeniedException.class,()->save(foreignTenant));
    }

    @Test void rechecksAgentOwnershipAfterInitialScopeCheckAndUserLock() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent-50","",Collections.singletonList(new SimpleGrantedAuthority("ROLE_AGENT"))));
        jdbc.update("UPDATE user_account SET parent_user_id=50 WHERE id=7001");
        // Another administrator reassigns the customer after the preliminary scope check.
        doAnswer(call -> {jdbc.update("UPDATE user_account SET parent_user_id=60 WHERE id=7001");return null;}).when(access).checkUser(7001L);
        ObjectNode input=input();input.putObject("profile").put("nickname","must not write");
        assertThrows(AccessDeniedException.class,()->save(input));
        assertNull(jdbc.queryForObject("SELECT nickname FROM user_account WHERE id=7001",String.class));
        reset(access);jdbc.update("UPDATE user_account SET parent_user_id=60 WHERE id=7001");
        assertThrows(AccessDeniedException.class,()->transaction.execute(status -> service.get(7001L)));
    }

    @Test void returnsTenantScopedParentIdentity() {
        jdbc.update("INSERT INTO user_account(tenant_id,id,row_version,email,password_hash,status,user_type,created_at,updated_at) VALUES (41,7003,0,'agent@example.com','hidden-hash','normal','agent',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("UPDATE user_account SET parent_user_id=7003 WHERE id=7001");
        Map<String,Object> response=transaction.execute(status -> service.get(7001L));
        Map<?,?> user=(Map<?,?>)((Map<?,?>)response.get("data")).get("user");
        assertEquals("agent@example.com",user.get("parentUserEmail"));
        jdbc.update("UPDATE user_account SET parent_user_id=7002 WHERE id=7001");
        response=transaction.execute(status -> service.get(7001L));
        user=(Map<?,?>)((Map<?,?>)response.get("data")).get("user");
        assertNull(user.get("parentUserEmail"));
    }

    @Test void decimalStringsSurviveActualJsonRoundTripWithoutLosingAccountPrecision() throws Exception {
        jdbc.update("UPDATE asset_account SET available=1.0000000000000001 WHERE user_id=7001");
        Map<String,Object> response=transaction.execute(status -> service.get(7001L));
        com.fasterxml.jackson.databind.JsonNode data=json.readTree(json.writeValueAsString(response)).get("data");
        assertTrue(data.get("user").get("fundBalance").isTextual());
        assertEquals("1.0000000000000001",data.get("user").get("fundBalance").textValue());
        assertEquals("1.0000000000000001",data.get("accounts").get(0).get("available").textValue());
        assertEquals("26.0000000000000001",data.get("totalBalance").textValue());
        assertFalse(data.get("user").has("passwordHash"));assertFalse(data.get("user").has("currentToken"));
    }
}
