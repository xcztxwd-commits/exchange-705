package com.gtcfesk.exchange.tenant;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
@Component
public class TenantSecrets {
 @Value("${tenant.secrets.key:}") private String key;
 public static final String MASK="********";
 public static boolean secret(String name){return name!=null&&name.toLowerCase(Locale.ROOT).matches(".*(password|secret|token|api[._-]?key).* ".trim());}
 public String encrypt(String name,String value){
  if(value==null||value.isEmpty())return value;
  try {byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);Cipher cipher=cipher(Cipher.ENCRYPT_MODE,name,nonce);byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));byte[] result=new byte[nonce.length+encrypted.length];System.arraycopy(nonce,0,result,0,nonce.length);System.arraycopy(encrypted,0,result,nonce.length,encrypted.length);return "enc:v1:"+Base64.getEncoder().encodeToString(result);}
  catch(Exception e){throw new IllegalStateException("租户密钥加密失败，请检查服务器密钥配置");}
 }
 public String decrypt(String name,String value){
  if(value==null||!value.startsWith("enc:v1:"))return value; // Historical plaintext must be migrated separately before production cutover.
  try {byte[] bytes=Base64.getDecoder().decode(value.substring(7));if(bytes.length<29)throw new IllegalArgumentException();Cipher cipher=cipher(Cipher.DECRYPT_MODE,name,Arrays.copyOf(bytes,12));return new String(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.UTF_8);}
  catch(Exception e){throw new IllegalStateException("租户密钥解密失败");}
 }
 private Cipher cipher(int mode,String name,byte[] nonce)throws Exception{
  byte[] bytes=Base64.getDecoder().decode(key);if(bytes.length!=32)throw new IllegalArgumentException();
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(bytes,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD((TenantContext.requireTenantId()+":"+name).getBytes(StandardCharsets.UTF_8));return cipher;
 }
}
