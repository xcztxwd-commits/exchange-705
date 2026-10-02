package com.gtcfesk.exchange.control;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.*;import javax.crypto.spec.*;
import java.nio.ByteBuffer;import java.nio.charset.StandardCharsets;import java.security.*;import java.util.*;
/** TOTP secrets are encrypted at rest with a deployment supplied 256-bit key. */
@Component
public class ControlMfa {
 private final String encryptionKey;
 public ControlMfa(@Value("${platform.mfa-encryption-key:}") String key){encryptionKey=key;}
 public String encrypt(String secret){try{byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);Cipher c=cipher(Cipher.ENCRYPT_MODE,nonce);byte[] value=c.doFinal(secret.getBytes(StandardCharsets.US_ASCII));return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length+value.length).put(nonce).put(value).array());}catch(Exception e){throw new IllegalStateException("MFA 密钥配置不可用");}}
 public String decrypt(String data){try{byte[] bytes=Base64.getDecoder().decode(data);if(bytes.length<29)throw new IllegalArgumentException();Cipher c=cipher(Cipher.DECRYPT_MODE,Arrays.copyOf(bytes,12));return new String(c.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.US_ASCII);}catch(Exception e){throw new IllegalStateException("MFA 密钥配置不可用");}}
 private Cipher cipher(int mode,byte[] nonce)throws Exception{byte[] key=Base64.getDecoder().decode(encryptionKey);if(key.length!=32)throw new IllegalArgumentException();Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));return c;}
 public boolean verify(String encrypted,String supplied){if(supplied==null||!supplied.matches("[0-9]{6}"))return false;String secret=decrypt(encrypted);long counter=System.currentTimeMillis()/30000;for(int offset=-1;offset<=1;offset++)if(MessageDigest.isEqual(code(secret,counter+offset).getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII)))return true;return false;}
 static String code(String secret,long counter){try{String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";int acc=0,bits=0;java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();for(char ch:secret.toUpperCase(Locale.ROOT).replace("=","").toCharArray()){int v=alphabet.indexOf(ch);if(v<0)throw new IllegalArgumentException();acc=(acc<<5)|v;bits+=5;if(bits>=8){bits-=8;out.write((acc>>bits)&255);}}Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(out.toByteArray(),"HmacSHA1"));byte[] hash=mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());int o=hash[hash.length-1]&15;int n=((hash[o]&127)<<24)|((hash[o+1]&255)<<16)|((hash[o+2]&255)<<8)|(hash[o+3]&255);return String.format(Locale.ROOT,"%06d",n%1000000);}catch(Exception e){throw new IllegalArgumentException("无效 MFA 密钥");}}
}
