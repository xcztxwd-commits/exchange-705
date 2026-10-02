package com.gtcfesk.exchange.common;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AudioValidationTest {
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
 @Test void audioExtensionAndMagicMustBothMatch(){
  byte[] wav="RIFF0000WAVEpayload".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  assertTrue(FileUploadController.validAudio(".wav",wav));assertFalse(FileUploadController.validAudio(".svg",wav));assertFalse(FileUploadController.validAudio(".mp3",wav));assertFalse(FileUploadController.validAudio(".wav","<svg onload='x'>".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));assertFalse(FileUploadController.validAudio(".ogg",new byte[0]));
 }
 @Test void realAudioUploadReturnsRealEnvironmentUrl() throws Exception { assertAudioUpload(false,"/api/uploads/audio/"); }
 @Test void demoAudioUploadReturnsDemoEnvironmentUrl() throws Exception { assertAudioUpload(true,"/demo-uploads/audio/"); }
 @Test void demoUploadProxyPreservesTenantHost() throws Exception {
  java.nio.file.Path config=java.nio.file.Paths.get(System.getProperty("basedir")).getParent().resolve("docker/nginx.conf");
  String nginx=new String(java.nio.file.Files.readAllBytes(config),java.nio.charset.StandardCharsets.UTF_8);
  java.util.regex.Matcher location=java.util.regex.Pattern.compile("location \\^~ /demo-uploads/ \\{([^}]+)\\}").matcher(nginx);
  assertTrue(location.find());
  assertTrue(location.group(1).contains("rewrite ^/demo-uploads/(.*)$ /uploads/$1 break;"));
  assertTrue(location.group(1).contains("proxy_pass http://$demo_backend;"));
  assertTrue(location.group(1).contains("proxy_set_header Host $host;"));
 }
 private void assertAudioUpload(boolean demo,String prefix) throws Exception {
  FileUploadController controller=new FileUploadController();
  UploadStorage storage=new UploadStorage(temporary.resolve("configured").toString());
  org.springframework.test.util.ReflectionTestUtils.setField(controller,"storage",storage);
  com.gtcfesk.exchange.simulation.SimulationEnvironment simulation=org.mockito.Mockito.mock(com.gtcfesk.exchange.simulation.SimulationEnvironment.class);
  org.mockito.Mockito.when(simulation.enabled()).thenReturn(demo);
  org.springframework.test.util.ReflectionTestUtils.setField(controller,"simulation",simulation);
  String subject=Long.toString(java.util.UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
  org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(subject,"",java.util.Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
  org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
  java.nio.file.Path file=null,ownerDirectory=null;
  byte[] wav="RIFF0000WAVEpayload".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  try(com.gtcfesk.exchange.tenant.TenantContext.Scope ignored=com.gtcfesk.exchange.tenant.TenantContext.open(999991L)) {
   String owner=com.gtcfesk.exchange.tenant.TenantFiles.ownerPath();
   java.nio.file.Path base=storage.audio();
   ownerDirectory=base.resolve(owner).normalize();assertTrue(ownerDirectory.startsWith(base.normalize()));
   org.springframework.http.ResponseEntity<?> response=controller.uploadAudio(auth,new org.springframework.mock.web.MockMultipartFile("file","tone.wav","audio/wav",wav));
   assertEquals(200,response.getStatusCodeValue());
   String url=(String)((java.util.Map<?,?>)response.getBody()).get("url");
   file=ownerDirectory.resolve(url.substring(url.lastIndexOf('/')+1));
   assertTrue(url.startsWith(prefix+owner+"/"),url);
   assertArrayEquals(wav,java.nio.file.Files.readAllBytes(file));
  } finally {
   org.springframework.security.core.context.SecurityContextHolder.clearContext();
   com.gtcfesk.exchange.tenant.TenantContext.clear();
   if(file!=null)java.nio.file.Files.deleteIfExists(file);
   if(ownerDirectory!=null)java.nio.file.Files.deleteIfExists(ownerDirectory);
  }
 }
}
