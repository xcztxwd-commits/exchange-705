package com.gtcfesk.exchange.tenant;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DedicatedMysqlFixtureTest {
 @Test void otherHostsPortsAndDatabaseNamesAreDeniedBeforeConnecting()throws Exception{
  for(String url:Arrays.asList("jdbc:mysql://127.0.0.1:3306/production?useSSL=false","jdbc:mysql://localhost:33318/mt705_probe_test?useSSL=false","jdbc:mysql://192.0.2.1:33318/mt705_probe_test?useSSL=false","jdbc:mysql://127.0.0.1:33318/business?useSSL=false")){
   Map<String,String> settings=new HashMap<>();settings.put("url",url);
   assertThrows(IllegalArgumentException.class,()->DedicatedMysqlFixture.open(settings));
  }
 }
 @Test void missingExplicitFixtureNeverFallsBackToH2(){
  assertThrows(IllegalStateException.class,()->DedicatedMysqlFixture.fromProperty("mt705.unconfigured.fixture"));
 }
 @Test void newRunNativePortAloneDoesNotProveDisposableRestore(){
  Map<String,String> settings=new HashMap<>();settings.put("url","jdbc:mysql://127.0.0.1:33418/mt705_probe_test?useSSL=false");
  assertThrows(NullPointerException.class,()->DedicatedMysqlFixture.open(settings));
 }
 @Test void nativeLoopbackPortAloneDoesNotProveDisposableRestore(){
  Map<String,String> settings=new HashMap<>();settings.put("url","jdbc:mysql://127.0.0.1:33318/mt705_probe_test?useSSL=false");
  assertThrows(NullPointerException.class,()->DedicatedMysqlFixture.open(settings));
 }
 @Test void dockerTransportDoesNotWidenApprovedHostOrPort(){
  Map<String,String> settings=new HashMap<>();settings.put("fixtureTransport","docker");
  for(String url:Arrays.asList("jdbc:mysql://127.0.0.1:64029/mt705_probe_test?useSSL=false","jdbc:mysql://127.0.0.1:33418/mt705_probe_test?useSSL=false")){
   settings.put("url",url);assertThrows(IllegalArgumentException.class,()->DedicatedMysqlFixture.open(settings));
  }
 }
 @Test void dockerRequiresExplicitSameRunIdentity(){
  assertThrows(IllegalArgumentException.class,()->DedicatedMysqlFixture.verifyDocker(new HashMap<>(),null));
 }
 @Test void dockerProofCannotChangeSourceIdentity()throws Exception{
  Map<String,String> settings=new HashMap<>();settings.put("runId","stage1-20261001-220237");settings.put("containerName","mt705-stage1-20261001-220237-mysql");settings.put("containerId",String.join("",Collections.nCopies(64,"a")));settings.put("serverUuid","fixture-uuid");
  com.fasterxml.jackson.databind.JsonNode proof=new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"source\":{\"run_id\":\"20261001-000000\"}}");
  assertThrows(IllegalArgumentException.class,()->DedicatedMysqlFixture.verifyDocker(settings,proof));
 }
}
