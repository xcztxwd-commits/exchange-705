package com.gtcfesk.exchange.common;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AudioValidationTest {
 @Test void audioExtensionAndMagicMustBothMatch(){
  byte[] wav="RIFF0000WAVEpayload".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  assertTrue(FileUploadController.validAudio(".wav",wav));assertFalse(FileUploadController.validAudio(".svg",wav));assertFalse(FileUploadController.validAudio(".mp3",wav));assertFalse(FileUploadController.validAudio(".wav","<svg onload='x'>".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));assertFalse(FileUploadController.validAudio(".ogg",new byte[0]));
 }
}
