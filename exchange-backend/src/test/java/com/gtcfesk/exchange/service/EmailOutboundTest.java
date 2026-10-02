package com.gtcfesk.exchange.service;
import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.security.OutboundEndpointPolicy;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class EmailOutboundTest {
 SystemConfigService configs;OutboundEndpointPolicy outbound;EmailService service;JavaMailSenderImpl sender;
 @BeforeEach void setup(){TenantContext.open(1L);configs=mock(SystemConfigService.class);outbound=mock(OutboundEndpointPolicy.class);sender=spy(new JavaMailSenderImpl());doNothing().when(sender).send(any(SimpleMailMessage.class));service=new EmailService(){@Override protected JavaMailSenderImpl newSender(){return sender;}};ReflectionTestUtils.setField(service,"systemConfigService",configs);ReflectionTestUtils.setField(service,"outbound",outbound);ReflectionTestUtils.setField(service,"security",mock(com.gtcfesk.exchange.security.RegistrationSecurity.class));Map<String,String> values=new HashMap<>();values.put("mail.host","smtp.example.com");values.put("mail.port","465");values.put("mail.username","sender@example.com");values.put("mail.password","test-only-not-a-secret");values.put("mail.from","sender@example.com");when(configs.getConfigValue(anyString())).thenAnswer(i->values.get(i.getArgument(0)));}
 @AfterEach void clean(){TenantContext.clear();}
 OutboundEndpointPolicy.Destination destination(int port)throws Exception{java.lang.reflect.Constructor<OutboundEndpointPolicy.Destination> c=OutboundEndpointPolicy.Destination.class.getDeclaredConstructor(String.class,int.class,InetAddress.class);c.setAccessible(true);return c.newInstance("smtp.example.com",port,InetAddress.getByName("127.0.0.1"));}
 @Test void tlsModesAndSocketPinningAndTimeoutsAreMandatory()throws Exception{for(int port:new int[]{465,587}){when(outbound.smtp(anyString(),anyString())).thenReturn(destination(port));JavaMailSenderImpl built=service.buildSender();Properties p=built.getJavaMailProperties();assertEquals("true",p.getProperty("mail.smtp.ssl.checkserveridentity"));assertEquals("false",p.getProperty("mail.smtp.socketFactory.fallback"));assertEquals("3000",p.getProperty("mail.smtp.connectiontimeout"));assertEquals("5000",p.getProperty("mail.smtp.timeout"));assertEquals(String.valueOf(port==587),p.getProperty("mail.smtp.starttls.required"));assertEquals(String.valueOf(port==465),p.getProperty("mail.smtp.ssl.enable"));assertTrue(p.get(port==465?"mail.smtp.ssl.socketFactory":"mail.smtp.socketFactory") instanceof EmailService.PinnedSockets);}}
 @Test void socketConnectUsesPreviouslyCheckedAddressNotSuppliedHost()throws Exception{try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){server.setSoTimeout(2000);try(Socket socket=new EmailService.PinnedSockets(destination(server.getLocalPort())).createSocket()){socket.connect(InetSocketAddress.createUnresolved("untrusted.invalid",server.getLocalPort()),1000);try(Socket accepted=server.accept()){assertTrue(socket.getInetAddress().isLoopbackAddress());}}}}
 @Test void defaultDenyInvalidRecipientAndMissingConfigNeverSend(){when(outbound.smtp(anyString(),anyString())).thenThrow(new com.gtcfesk.exchange.common.BusinessException("SMTP 目标未获平台出站授权"));assertThrows(RuntimeException.class,()->service.sendVerificationCode("recipient@example.com","123456"));assertThrows(RuntimeException.class,()->service.sendVerificationCode("recipient@example.com\r\nBcc:bad@example.com","123456"));assertThrows(RuntimeException.class,()->service.sendVerificationCode("recipient@example.com","garbage"));when(configs.getConfigValue("mail.from")).thenReturn(null);assertThrows(RuntimeException.class,()->service.sendPasswordResetNotice("recipient@example.com"));verify(sender,never()).send(any(SimpleMailMessage.class));}
 @Test void validatedMessageUsesSingleRecipientWithoutRealSending()throws Exception{when(outbound.smtp(anyString(),anyString())).thenReturn(destination(465));service.sendVerificationCode("recipient@example.com","123456");org.mockito.ArgumentCaptor<SimpleMailMessage> capture=org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);verify(sender).send(capture.capture());assertArrayEquals(new String[]{"recipient@example.com"},capture.getValue().getTo());assertNull(capture.getValue().getBcc());}
 @Test void diagnosticsContainOnlyTenantAndBoundedClassNamesNeverMailSecrets()throws Exception{
  when(outbound.smtp(anyString(),anyString())).thenReturn(destination(465));
  ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(EmailService.class);
  ch.qos.logback.classic.Level previousLevel=logger.getLevel();logger.setLevel(ch.qos.logback.classic.Level.WARN);
  ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender=new ch.qos.logback.core.read.ListAppender<>();appender.start();logger.addAppender(appender);
  try{
   Map<Object,Exception> failures=new HashMap<>();failures.put(new SimpleMailMessage(),new javax.mail.MessagingException("recipient@example.com 123456 test-only-not-a-secret",new java.net.SocketTimeoutException("secret SMTP password")));
   doThrow(new org.springframework.mail.MailSendException("must never log this original message",null,failures)).when(sender).send(any(SimpleMailMessage.class));
   com.gtcfesk.exchange.common.BusinessException exposed=assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->service.sendVerificationCode("recipient@example.com","123456"));
   assertEquals("邮件发送失败：请检查平台出站授权、TLS 证书及 SMTP 凭据",exposed.getMessage());assertEquals(1,appender.list.size());
   String log=appender.list.get(0).getFormattedMessage();assertTrue(log.contains("tenant=1"));assertTrue(log.contains("java.net.SocketTimeoutException"));assertTrue(log.contains("javax.mail.MessagingException"));
   for(String secret:new String[]{"recipient@example.com","123456","test-only-not-a-secret","secret SMTP password","must never log"}){assertFalse(log.contains(secret));assertFalse(exposed.getMessage().contains(secret));}
   assertNull(appender.list.get(0).getThrowableProxy());verify(sender).send(any(SimpleMailMessage.class));
  }finally{logger.detachAppender(appender);appender.stop();logger.setLevel(previousLevel);}
 }
 @Test void diagnosticCauseCyclesAreBounded(){RuntimeException first=new RuntimeException("hidden one"),second=new RuntimeException("hidden two");first.initCause(second);second.initCause(first);assertEquals("java.lang.RuntimeException",EmailService.exceptionTypes(first));}

}
