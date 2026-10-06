package com.gtcfesk.exchange.market;

import java.util.Map;

/** Owned fixture only. A real JVM dies after its accepted receipt has committed, before any worker runs. */
public final class ControlRecoveryAcceptanceCrashChild {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Original request key required");
        ControlRecoveryMysqlTest.ownedFixture();
        ControlRecoveryMysqlTest app=new ControlRecoveryMysqlTest();app.setup();
        Map<String,Object> receipt=app.accept(args[0]);
        System.out.println("CONTROL_RECOVERY_ACCEPTED_COMMITTED "+receipt.get("commandId")+" "+app.symbol.getId());
        System.out.flush();Runtime.getRuntime().halt(74);
    }
}
