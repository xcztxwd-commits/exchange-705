package com.gtcfesk.exchange.service;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.security.OutboundEndpointPolicy;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import javax.mail.internet.InternetAddress;
import javax.net.SocketFactory;
import java.io.IOException;
import java.net.*;
import java.util.Properties;
import java.util.concurrent.*;

@Service
public class EmailService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(EmailService.class);
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private OutboundEndpointPolicy outbound;
    @Autowired private com.gtcfesk.exchange.security.RegistrationSecurity security;
    // ponytail: four synchronous sends per instance; use an audited queue if throughput requires more.
    private static final Semaphore SENDING = new Semaphore(4);
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"smtp-deadline");t.setDaemon(true);return t;});

    JavaMailSenderImpl buildSender() {
        TenantContext.requireTenantId();
        String username=required("mail.username"),password=required("mail.password");
        if(username.length()>254||password.length()>1024)throw new BusinessException("SMTP 凭据长度无效");
        OutboundEndpointPolicy.Destination destination=outbound.smtp(required("mail.host"),required("mail.port"));
        JavaMailSenderImpl sender=newSender();sender.setHost(destination.host);sender.setPort(destination.port);
        sender.setUsername(username);sender.setPassword(password);sender.setDefaultEncoding("UTF-8");
        Properties properties=sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth","true");properties.setProperty("mail.smtp.ssl.checkserveridentity","true");
        properties.setProperty("mail.smtp.ssl.protocols","TLSv1.2 TLSv1.3");
        properties.setProperty("mail.smtp.ssl.enable",String.valueOf(destination.port==465));
        properties.setProperty("mail.smtp.starttls.enable",String.valueOf(destination.port==587));
        properties.setProperty("mail.smtp.starttls.required",String.valueOf(destination.port==587));
        properties.setProperty("mail.smtp.connectiontimeout","3000");properties.setProperty("mail.smtp.timeout","5000");properties.setProperty("mail.smtp.writetimeout","5000");
        properties.setProperty("mail.smtp.quitwait","false");properties.setProperty("mail.smtp.socketFactory.fallback","false");
        properties.put(destination.port==465?"mail.smtp.ssl.socketFactory":"mail.smtp.socketFactory",new PinnedSockets(destination));
        return sender;
    }
    protected JavaMailSenderImpl newSender(){return new JavaMailSenderImpl();}
    public void sendVerificationCode(String email,String code){if(code==null||!code.matches("[0-9]{6}"))throw new BusinessException("验证码格式无效");send(email,"验证码","您的验证码是："+code+"，有效期10分钟。");}
    public void sendPasswordResetNotice(String email){send(email,"密码重置通知","您的密码已被重置，请及时登录并修改密码。");}
    private void send(String recipient,String subject,String body) {
        address(recipient);String from=required("mail.from");address(from);
        if(!SENDING.tryAcquire())throw new BusinessException("邮件发送繁忙，请稍后重试");
        try {
            JavaMailSenderImpl sender=buildSender();security.limitEmail(recipient);SimpleMailMessage message=new SimpleMailMessage();
            message.setFrom(from);message.setTo(recipient);message.setSubject(subject);message.setText(body);
            try {sender.send(message);}catch(Exception e){LOG.warn("SMTP delivery failed; tenant={} exceptionTypes={}",TenantContext.requireTenantId(),exceptionTypes(e));throw new BusinessException("邮件发送失败：请检查平台出站授权、TLS 证书及 SMTP 凭据");}
        }finally{SENDING.release();}
    }
    /** Class names only: exceptions may contain credentials, addresses, MIME bodies or verification codes. */
    static String exceptionTypes(Throwable failure){
        java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        java.util.Set<String> types=new java.util.LinkedHashSet<>();java.util.Deque<Throwable> queue=new java.util.ArrayDeque<>();
        if(failure!=null)queue.add(failure);
        while(!queue.isEmpty()&&seen.size()<12){
            Throwable next=queue.remove();if(!seen.add(next))continue;types.add(next.getClass().getName());
            if(next.getCause()!=null)queue.add(next.getCause());
            if(next instanceof org.springframework.mail.MailSendException){Exception[] messages=((org.springframework.mail.MailSendException)next).getMessageExceptions();for(int i=0;i<messages.length&&i<12;i++)if(messages[i]!=null)queue.add(messages[i]);}
        }
        return String.join(",",types);
    }
    private String required(String key){String value=systemConfigService.getConfigValue(key);if(value==null||value.trim().isEmpty())throw new BusinessException("SMTP 配置缺失："+key);return value;}
    public static void address(String value){try{if(value==null||value.length()>254||value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw new Exception();InternetAddress a=new InternetAddress(value,true);a.validate();if(!value.equals(a.getAddress())||a.getPersonal()!=null)throw new Exception();}catch(Exception e){throw new BusinessException("邮件地址格式无效");}}

    /** JavaMail keeps the original hostname for SNI/certificate checks, but TCP never re-resolves it. */
    static final class PinnedSockets extends SocketFactory {
        private final OutboundEndpointPolicy.Destination target;
        PinnedSockets(OutboundEndpointPolicy.Destination target){this.target=target;}
        @Override public Socket createSocket(){return new Socket(){
            private ScheduledFuture<?> deadline;
            @Override public void connect(SocketAddress ignored,int timeout)throws IOException{
                if(!(ignored instanceof InetSocketAddress)||((InetSocketAddress)ignored).getPort()!=target.port)throw new IOException("SMTP destination changed");
                deadline=DEADLINES.schedule(()->{try{close();}catch(IOException ignoredClose){}},20,TimeUnit.SECONDS);
                try{super.connect(new InetSocketAddress(target.address,target.port),Math.min(timeout>0?timeout:3000,3000));setSoTimeout(5000);}catch(IOException e){close();throw e;}
            }
            @Override public void connect(SocketAddress endpoint)throws IOException{connect(endpoint,3000);}
            @Override public synchronized void close()throws IOException{if(deadline!=null)deadline.cancel(false);super.close();}
        };}
        @Override public Socket createSocket(String h,int p)throws IOException{Socket s=createSocket();s.connect(InetSocketAddress.createUnresolved(h,p));return s;}
        @Override public Socket createSocket(String h,int p,InetAddress local,int lp)throws IOException{return createSocket(h,p);}
        @Override public Socket createSocket(InetAddress h,int p)throws IOException{return createSocket(h.getHostAddress(),p);}
        @Override public Socket createSocket(InetAddress h,int p,InetAddress local,int lp)throws IOException{return createSocket(h.getHostAddress(),p);}
    }
}
