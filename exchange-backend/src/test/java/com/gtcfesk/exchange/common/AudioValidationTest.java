package com.gtcfesk.exchange.common;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AudioValidationTest {
 @Test void audioExtensionAndMagicMustBothMatch(){
  byte[] wav="RIFF0000WAVEpayload".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  assertTrue(FileUploadController.validAudio(".wav",wav));assertFalse(FileUploadController.validAudio(".svg",wav));assertFalse(FileUploadController.validAudio(".mp3",wav));assertFalse(FileUploadController.validAudio(".wav","<svg onload='x'>".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));assertFalse(FileUploadController.validAudio(".ogg",new byte[0]));
 }
 @Test void realAudioUploadReturnsRealEnvironmentUrl() throws Exception { assertAudioUpload(false,"/api/uploads/audio/"); }
 @Test void demoAudioUploadReturnsDemoEnvironmentUrl() throws Exception { assertAudioUpload(true,"/demo-uploads/audio/"); }
 private void assertAudioUpload(boolean demo,String prefix) throws Exception {
  FileUploadController controller=new FileUploadController();
  com.gtcfesk.exchange.simulation.SimulationEnvironment simulation=org.mockito.Mockito.mock(com.gtcfesk.exchange.simulation.SimulationEnvironment.class);
  org.mockito.Mockito.when(simulation.enabled()).thenReturn(demo);
  org.springframework.test.util.ReflectionTestUtils.setField(controller,"simulation",simulation);
  String subject=Long.toString(java.util.UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
  org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(subject,"",java.util.Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
  org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
  java.nio.file.Path file=null;
  byte[] wav="RIFF0000WAVEpayload".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  try {
   java.nio.file.Path base=(java.nio.file.Path)org.springframework.test.util.ReflectionTestUtils.getField(FileUploadController.class,"AUDIO_DIR_PATH");
   org.springframework.http.ResponseEntity<?> response=controller.uploadAudio(auth,new org.springframework.mock.web.MockMultipartFile("file","tone.wav","audio/wav",wav));
   assertEquals(200,response.getStatusCodeValue());
   String url=(String)((java.util.Map<?,?>)response.getBody()).get("url");
   file=base.resolve(url.substring(url.lastIndexOf('/')+1));
   assertTrue(url.startsWith(prefix),url);
   assertArrayEquals(wav,java.nio.file.Files.readAllBytes(file));
  } finally {
   org.springframework.security.core.context.SecurityContextHolder.clearContext();
   if(file!=null)java.nio.file.Files.deleteIfExists(file);
  }
 }
}
