package com.gtcfesk.exchange.tenant;
import org.junit.jupiter.api.extension.*;
import java.util.concurrent.Callable;
/** Explicit tenant 1 belongs only to these disposable fixtures, not a production fallback. */
public class TenantOneFixture implements BeforeEachCallback,AfterEachCallback {
 public void beforeEach(ExtensionContext c){TenantContext.clear();TenantContext.open(1L);}
 public void afterEach(ExtensionContext c){TenantContext.clear();}
 public static <T> Callable<T> worker(Callable<T> work){return ()->{try(TenantContext.Scope ignored=TenantContext.open(1L)){return work.call();}};}
}
