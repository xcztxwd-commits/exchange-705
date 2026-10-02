package com.gtcfesk.exchange.tenant;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
class TenantBoundaryTest {
 @AfterEach void clear(){TenantContext.clear();}
 @Test void missingContextIsDenied(){assertThrows(AccessDeniedException.class,TenantContext::requireTenantId);}
 @Test void cannotSwitchOrMutateOwner(){try(TenantContext.Scope ignored=TenantContext.open(2L)){
  assertThrows(AccessDeniedException.class,()->TenantContext.open(3L));
  com.gtcfesk.exchange.entity.UserAccount u=new com.gtcfesk.exchange.entity.UserAccount();u.assignTenantBeforeInsert();assertEquals(2L,u.getTenantId());
  assertThrows(AccessDeniedException.class,()->u.setTenantId(3L));
 }assertNull(TenantContext.currentTenantId());}
 @Test void exceptionDoesNotLeakThreadState(){assertThrows(IllegalStateException.class,()->{try(TenantContext.Scope ignored=TenantContext.open(2L)){throw new IllegalStateException();}});assertNull(TenantContext.currentTenantId());}
 @Test void childThreadCannotInheritIdentity()throws Exception{ExecutorService pool=Executors.newSingleThreadExecutor();try(TenantContext.Scope ignored=TenantContext.open(2L)){
  assertNull(pool.submit(TenantContext::currentTenantId).get());
  assertTrue(pool.submit(()->{try(TenantContext.Scope child=TenantContext.open(3L)){return TenantContext.requireTenantId()==3L;}}).get());
  assertNull(pool.submit(TenantContext::currentTenantId).get());
 }finally{pool.shutdownNow();}}
 @Test void transactionCannotSwitchAfterScopeClosed(){
  TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
  try{try(TenantContext.Scope ignored=TenantContext.open(2L)){assertEquals(2L,TenantContext.requireTenantId());}
   assertThrows(AccessDeniedException.class,()->TenantContext.open(3L));
  }finally{TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(0));TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
 }
 @Test void privateRepositoryDoesNotExposeUnscopedCrud(){
  for(java.lang.reflect.Method method:com.gtcfesk.exchange.repository.UserAccountRepository.class.getMethods())
   assertFalse(java.util.Arrays.asList("findAll","findById","deleteAll","deleteById","count").contains(method.getName()),method.toString());
 }
}
