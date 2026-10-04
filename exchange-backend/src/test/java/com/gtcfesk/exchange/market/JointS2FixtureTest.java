package com.gtcfesk.exchange.market;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure settings rejection checks. Never opens a connection or fabricates a restore proof. */
class JointS2FixtureTest {
    private Map<String,String> settings(){
        Map<String,String> value=new HashMap<>();
        value.put("fixtureTransport","docker");value.put("runId",JointS2Fixture.RUN);value.put("jointOwner",JointS2Fixture.OWNER);value.put("jointRun",JointS2Fixture.JOINT_RUN);
        value.put("containerId",JointS2Fixture.MYSQL);value.put("containerName","mt705-"+JointS2Fixture.RUN+"-mysql");value.put("serverUuid",JointS2Fixture.MYSQL_UUID);
        value.put("jointS2SchemaEpoch","2026100403");value.put("url","jdbc:mysql://127.0.0.1:33318/mt705_probe_joint_s2_403_0123456789abcdef?useSSL=false");
        value.put("username","unit-settings-validation-only");value.put("password","unit-noncredential-never-used-for-connection");
        for(String key:Arrays.asList("jointResources","redisResources","fixtureProof")){value.put(key,"unit-file-does-not-exist");value.put(key+"Sha256",String.join("",Collections.nCopies(64,"a")));}
        return value;
    }
    @Test void syntacticallyValidSettingsStillDoNotOpenAnything(){assertDoesNotThrow(()->JointS2Fixture.validateSettings(settings()));}
    @Test void oldTaskOwnerAndPortsNeverQualifyAsCurrentJoint(){
        for(String[] change:Arrays.asList(new String[]{"jointOwner","01a10171-9c92-76f2-837c-6489387190b5"},new String[]{"runId","stage1-20261003-221105"},new String[]{"jointRun","foreign"},new String[]{"url","jdbc:mysql://127.0.0.1:33358/mt705_s2_resume_0123456789abcdef?useSSL=false"})){Map<String,String> value=settings();value.put(change[0],change[1]);assertThrows(IllegalArgumentException.class,()->JointS2Fixture.validateSettings(value));}
    }
    @Test void missingShortOrForeignContainerIdsFailBeforeDocker(){
        for(String id:Arrays.asList("0d9216dab0cd",String.join("",Collections.nCopies(64,"b")),"")){Map<String,String> value=settings();value.put("containerId",id);assertThrows(IllegalArgumentException.class,()->JointS2Fixture.validateSettings(value));}
    }
    @Test void hostNamespaceAndSchemaFallbackAreRejected(){
        for(String url:Arrays.asList("jdbc:mysql://localhost:33318/mt705_probe_joint_s2_403_0123456789abcdef?useSSL=false","jdbc:mysql://127.0.0.1:33318/production?useSSL=false","jdbc:mysql://127.0.0.1:33318/mt705_probe_joint_local403_0123456789abcdef?useSSL=false")){Map<String,String> value=settings();value.put("url",url);assertThrows(IllegalArgumentException.class,()->JointS2Fixture.validateSettings(value));}
        Map<String,String> value=settings();value.put("jointS2SchemaEpoch","2026100305");assertThrows(IllegalArgumentException.class,()->JointS2Fixture.validateSettings(value));
    }
    @Test void anonymousAccountAndUnhashedEvidenceCannotQualify(){
        for(String[] change:Arrays.asList(new String[]{"username","root"},new String[]{"password",""},new String[]{"fixtureProofSha256","unsealed"},new String[]{"jointResources",""},new String[]{"redisResourcesSha256",""})){Map<String,String> value=settings();value.put(change[0],change[1]);assertThrows(IllegalArgumentException.class,()->JointS2Fixture.validateSettings(value));}
    }
}
