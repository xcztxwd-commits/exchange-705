package com.gtcfesk.exchange.tenant;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full Maven test builds reject unreviewed isolation surfaces; no database or application is started. */
class TenantSourceGateTest {
    @Test void productionSourceMatchesReviewedIsolationRegistry() throws Exception {
        Path root=Paths.get("").toAbsolutePath();
        if(!Files.isDirectory(root.resolve("scripts/multitenant")))root=root.getParent();
        String python=System.getenv().getOrDefault("PYTHON_EXECUTABLE","python");
        Process process=new ProcessBuilder(python,"scripts/multitenant/isolation_gate.py","--check")
                .directory(root.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int read;
        try(InputStream input=process.getInputStream()){while((read=input.read(buffer))!=-1)output.write(buffer,0,read);}
        assertEquals(0,process.waitFor(),new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
    }
}
