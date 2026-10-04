package com.gtcfesk.exchange.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.AdminUser;
import com.gtcfesk.exchange.admin.AdminUserRepository;
import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.control.ControlAuditLogRepository;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.control.TenantPolicyService;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantOneFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fixed-rate preparation and ordinary success audit use the actual production transaction template. */
@SpringJUnitConfig(DepositOrderServiceTest.Config.class)
@org.junit.jupiter.api.extension.ExtendWith(TenantOneFixture.class)
class DepositPreparationAndAuditRegressionTest {
    @Autowired DepositRecordRepository records;
    @Autowired DepositCreditRecordRepository credits;
    @Autowired AssetAccountRepository assets;
    @Autowired UserAccountRepository users;
    @Autowired AdminUserRepository admins;
    @Autowired DepositSettingRepository channels;
    @Autowired ControlAuditLogRepository auditLogs;
    @Autowired PlatformTransactionManager manager;
    @PersistenceContext EntityManager em;
    Long user;
    ForexQuoteMarketService quotes;
    @BeforeEach void setup(){
        UserAccount customer=new UserAccount();customer.setEmail(UUID.randomUUID()+"@deposit-preparation.invalid");customer.setPasswordHash("not-a-login");user=users.saveAndFlush(customer).getId();
        AssetAccount fund=new AssetAccount();fund.setUserId(user);fund.setCoin("FUND");fund.setAvailable(new BigDecimal("25"));assets.saveAndFlush(fund);
        AdminUser actor=new AdminUser();actor.setAccount(UUID.randomUUID().toString());actor.setEmail(actor.getAccount()+"@deposit-preparation.invalid");actor.setPasswordHash("not-a-login");actor.setRole("super_admin");actor=admins.saveAndFlush(actor);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor.getId().toString(),null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        DepositSetting channel=new DepositSetting();channel.setType("bank");channel.setEnabled(true);channel.setBankAccount("OWNED-SYNTHETIC-CHANNEL-"+user);channels.saveAndFlush(channel);
        quotes=mock(ForexQuoteMarketService.class);when(quotes.requireConversionRate("USD","yahoo")).thenReturn(BigDecimal.ONE);when(quotes.requireConversionRate("EUR","yahoo")).thenAnswer(invocation->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Conversion preparation must not own the funds transaction");return new BigDecimal("1.1");});
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    ControlAuditService audit(){return new ControlAuditService(auditLogs);}
    DepositOrderService service(String fault,boolean failAudit){
        DepositOrderService service=new DepositOrderService(records,credits,assets,users,admins,new FiatCurrencyService(quotes),mock(BackendAccess.class),new ObjectMapper(),manager){@Override protected void checkpoint(String stage){if(stage.equals(fault))throw new IllegalStateException("injected "+stage);}};
        ControlAuditService audit=failAudit?new ControlAuditService(auditLogs){@Override public void record(Long actor,Long tenant,String session,String action,String object,String outcome,String detail,String reason){throw new IllegalStateException("injected required audit unavailable");}}:audit();
        ReflectionTestUtils.setField(service,"controlAudit",audit);ReflectionTestUtils.setField(service,"tenantPolicy",mock(TenantPolicyService.class));ReflectionTestUtils.setField(service,"channels",channels);ReflectionTestUtils.setField(service,"entityManager",em);return service;
    }
    DepositOrderRequest request(){DepositOrderRequest input=new DepositOrderRequest();input.userId=user;input.amount=new BigDecimal("100");input.remark="Owned synthetic adjustment";input.idempotencyKey=UUID.randomUUID().toString();return input;}
    DepositOrderRequest submitted(){DepositOrderRequest input=request();input.type="bank";input.network="BANK";input.address="OWNED-SYNTHETIC-CHANNEL-"+user;return input;}
    void money(String expected){assertEquals(0,new BigDecimal(expected).compareTo(assets.findByTenantIdAndUserIdAndCoin(1L,user,"FUND").orElseThrow().getAvailable()));}
    long audits(String action){return em.createQuery("select count(a) from ControlAuditLog a where a.tenantId=1 and a.actorId is null and a.action=:action and a.detail like :user",Long.class).setParameter("action",action).setParameter("user","%userId="+user+";%").getSingleResult();}
    @Test void fixedDepositConversionIsPreparedOutsideTransactionAndNeverRevaluedOnReplayOrReview(){
        DepositOrderService service=service(null,false);DepositOrderRequest input=submitted();input.currency="EUR";DepositRecord record=service.submit(user,input);money("25");
        when(quotes.requireConversionRate("EUR","yahoo")).thenThrow(new com.gtcfesk.exchange.common.BusinessException("expired"));
        UserAccountRepository lockedUsers=mock(UserAccountRepository.class,org.mockito.AdditionalAnswers.delegatesTo(users));AssetAccountRepository lockedAssets=mock(AssetAccountRepository.class,org.mockito.AdditionalAnswers.delegatesTo(assets));DepositRecordRepository currentRecords=mock(DepositRecordRepository.class,org.mockito.AdditionalAnswers.delegatesTo(records));
        ReflectionTestUtils.setField(service,"users",lockedUsers);ReflectionTestUtils.setField(service,"assets",lockedAssets);ReflectionTestUtils.setField(service,"records",currentRecords);
        assertEquals(record.getId(),service.submit(user,input).getId());
        org.mockito.InOrder ordered=inOrder(lockedUsers,lockedAssets,currentRecords);ordered.verify(lockedUsers).lockById(user);ordered.verify(lockedAssets).lockByUserId(user);ordered.verify(currentRecords).findByTenantIdAndCreatedByTypeAndCreatedByIdAndIdempotencyKey(1L,"USER",user,input.idempotencyKey);
        service.review(record.getId(),true,null);money("135");verify(quotes,times(1)).requireConversionRate("EUR","yahoo");assertEquals(1,audits("DEPOSIT_CREATE"));assertEquals(1,audits("DEPOSIT_REVIEW"));
    }
    @Test void terminalOrderAndRequiredOrdinaryAuditFailureCannotLeaveCreditOrCancellation(){
        for(String fault:Arrays.asList("terminal-order","audit")){
            assertThrows(RuntimeException.class,()->service(fault,false).manual(request()));money("25");assertTrue(records.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L,user).isEmpty());assertEquals(0,audits("DEPOSIT_CREATE"));
        }
        assertThrows(RuntimeException.class,()->service(null,true).manual(request()));money("25");assertTrue(records.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L,user).isEmpty());assertEquals(0,audits("DEPOSIT_CREATE"));
        DepositRecord pending=service(null,false).submit(user,submitted());
        for(String fault:Arrays.asList("terminal-order","audit")){
            assertThrows(RuntimeException.class,()->service(fault,false).review(pending.getId(),true,null));money("25");assertEquals("PENDING",records.findByTenantIdAndId(1L,pending.getId()).orElseThrow().getStatus());assertFalse(credits.findByTenantIdAndDepositRecordId(1L,pending.getId()).isPresent());assertEquals(0,audits("DEPOSIT_REVIEW"));
        }
        assertThrows(RuntimeException.class,()->service(null,true).review(pending.getId(),true,null));money("25");assertEquals("PENDING",records.findByTenantIdAndId(1L,pending.getId()).orElseThrow().getStatus());assertFalse(credits.findByTenantIdAndDepositRecordId(1L,pending.getId()).isPresent());
        for(String fault:Arrays.asList("cancel-order","cancel-audit")){
            assertThrows(RuntimeException.class,()->service(fault,false).cancel(user,pending.getId()));assertEquals("PENDING",records.findByTenantIdAndId(1L,pending.getId()).orElseThrow().getStatus());money("25");assertEquals(0,audits("DEPOSIT_CANCEL"));
        }
        assertThrows(RuntimeException.class,()->service(null,true).cancel(user,pending.getId()));assertEquals("PENDING",records.findByTenantIdAndId(1L,pending.getId()).orElseThrow().getStatus());money("25");
        service(null,false).cancel(user,pending.getId());service(null,false).cancel(user,pending.getId());assertEquals(1,audits("DEPOSIT_CANCEL"));assertFalse(credits.findByTenantIdAndDepositRecordId(1L,pending.getId()).isPresent());
    }
}
