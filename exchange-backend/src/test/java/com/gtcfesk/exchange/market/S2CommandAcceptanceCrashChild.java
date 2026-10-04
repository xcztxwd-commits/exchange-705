package com.gtcfesk.exchange.market;

import java.util.Map;

/** Only the verified disposable S2 fixture. Halt occurs after a real ACCEPTED transaction returns. */
public final class S2CommandAcceptanceCrashChild {
    public static void main(String[] arguments) throws Exception {
        if(arguments.length!=1)throw new IllegalArgumentException("one private acceptance request key required");
        S2RuntimeMysqlTest.identity();S2CommandAcceptanceTest fixture=new S2CommandAcceptanceTest();fixture.setup();
        S2CommandAcceptanceTest.backendIdentity("17","ROLE_ADMIN");Map<String,Object> receipt=fixture.accept(arguments[0]);
        if(!"ACCEPTED".equals(receipt.get("state")))throw new IllegalStateException("durable ACCEPTED receipt required before halt");
        System.out.println("S2_ACCEPTED_COMMITTED "+receipt.get("commandId")+" "+fixture.symbol.getId());System.out.flush();
        Runtime.getRuntime().halt(74);
    }
}
