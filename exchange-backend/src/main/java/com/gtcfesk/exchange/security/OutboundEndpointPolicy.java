package com.gtcfesk.exchange.security;

import com.gtcfesk.exchange.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Operator-only exact destinations. Tenant configuration cannot grant outbound access. */
@Component
public class OutboundEndpointPolicy {
    @Value("${platform.outbound.smtp-endpoints:}") private String smtpEndpoints = "";
    @Value("${platform.outbound.support-origins:}") private String supportOrigins = "";
    @Value("${platform.outbound.callback-origins:}") private String callbackOrigins = "";
    @Value("${platform.outbound.local-loopback-enabled:false}") private boolean localLoopbackEnabled;
    @Value("${platform.outbound.local-routing-port:443}") private int localRoutingPort=443;
    @Value("${platform.outbound.local-loopback-endpoints:}") private String localLoopbackEndpoints = "";
    private static final ExecutorService DNS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(4), r -> { Thread t=new Thread(r,"outbound-dns");t.setDaemon(true);return t; }, new ThreadPoolExecutor.AbortPolicy());

    private static final Semaphore ROUTING = new Semaphore(4);
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"routing-deadline");t.setDaemon(true);return t;});

    public static final class Destination {
        public final String host; public final int port; public final InetAddress address;
        Destination(String host,int port,InetAddress address){this.host=host;this.port=port;this.address=address;}
    }
    public Destination smtp(String rawHost,String rawPort) {
        int port=port(rawPort); String host=destinationHost(rawHost,port);
        if(port!=465 && port!=587) throw error("SMTP 仅支持 465 隐式 TLS 或 587 强制 STARTTLS");
        allow(smtpEndpoints,host+":"+port,"SMTP 目标未获平台出站授权");
        return new Destination(host,port,destinationAddress(host,port));
    }
    public void smtpHost(String raw) {
        for(int tlsPort:new int[]{465,587})if(localDestination(raw,tlsPort)&&entries(smtpEndpoints).contains(raw.toLowerCase(Locale.ROOT)+":"+tlsPort)){smtp(raw,String.valueOf(tlsPort));return;}
        String host=hostname(raw);
        if(!entries(smtpEndpoints).contains(host+":465")&&!entries(smtpEndpoints).contains(host+":587"))throw error("SMTP 主机未获平台出站授权");
        publicAddresses(host);
    }
    public URI https(String value,String purpose) {
        try {
            if(value==null||value.length()>2048||!value.equals(value.trim())||value.indexOf('\\')>=0)throw error("外部地址格式无效");
            URI uri=URI.create(value);
            if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getHost()==null||uri.getFragment()!=null)throw error("外部地址必须为无凭据、无片段的 HTTPS URL");
            String host=hostname(uri.getHost());int port=uri.getPort()==-1?443:uri.getPort();
            if(port<1||port>65535)throw error("外部地址端口无效");
            String origin="https://"+host+(port==443?"":":"+port);
            String allowed="support".equals(purpose)?supportOrigins:"callback".equals(purpose)?callbackOrigins:null;
            if(allowed==null)throw error("未知外部地址用途");
            allow(allowed,origin,"外部地址未获平台出站授权");publicAddresses(host);return uri;
        } catch(IllegalArgumentException e){throw error("外部地址格式无效");}
    }
    public void validateConfig(String key,String value) {
        if(value==null||value.trim().isEmpty())return; // Clearing never grants access; readiness/send rejects incomplete values.
        if("mail.host".equals(key))smtpHost(value);
        else if("mail.port".equals(key)){int p=port(value);if(p!=465&&p!=587)throw error("SMTP 仅支持 TLS 端口 465/587");}
        else if("customer.service.link".equals(key))https(value,"support");
        else if(key!=null&&key.toLowerCase(Locale.ROOT).matches(".*(?:callback|webhook|notify)[._-]?(?:url|uri|endpoint)$"))https(value,"callback");
    }
    /** Fixed routing probe: TCP uses checked IP, TLS/Host use the original assigned hostname; no redirects. */
    public byte[] routingCheck(String rawHost){return routingCheck(rawHost,null);}
    public byte[] routingCheck(String rawHost,String challenge) {
        if(challenge!=null&&!challenge.matches("[0-9a-f]{32}"))throw error("路由挑战无效");
        String path="/api/tenant-routing-check"+(challenge==null?"":"?challenge="+challenge);
        int routingPort=routingPort(rawHost);
        String host=destinationHost(rawHost,routingPort);InetAddress address=destinationAddress(host,routingPort);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        if(!ROUTING.tryAcquire())throw error("路由检查繁忙");
        ScheduledFuture<?> deadline=null;
        try(java.net.Socket raw=new java.net.Socket()){
            deadline=DEADLINES.schedule(()->{try{raw.close();}catch(java.io.IOException ignored){}},10,TimeUnit.SECONDS);
            raw.connect(new InetSocketAddress(address,routingPort),3000);raw.setSoTimeout(5000);
            try(javax.net.ssl.SSLSocket socket=(javax.net.ssl.SSLSocket)((javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault()).createSocket(raw,host,routingPort,true)){
                javax.net.ssl.SSLParameters parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");parameters.setServerNames(Collections.singletonList(new javax.net.ssl.SNIHostName(host)));socket.setSSLParameters(parameters);socket.startHandshake();
                socket.getOutputStream().write(("GET "+path+" HTTP/1.0\r\nHost: "+host+"\r\nConnection: close\r\nAccept: application/json\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                java.io.ByteArrayOutputStream received=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[512];int n;
                while(true){long remaining=TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime());if(remaining<=0)throw error("路由检查超时");socket.setSoTimeout((int)Math.min(remaining,5000));n=socket.getInputStream().read(buffer);if(n<0)break;if(received.size()+n>8448)throw error("路由响应过大");received.write(buffer,0,n);}
                byte[] bytes=received.toByteArray();String response=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1);int separator=response.indexOf("\r\n\r\n");
                if(separator<0||separator>8192||!response.startsWith("HTTP/1.0 200 ")&&!response.startsWith("HTTP/1.1 200 ")||response.substring(0,separator).toLowerCase(Locale.ROOT).contains("transfer-encoding:"))throw error("路由检查未返回有效 HTTP 200");
                byte[] body=Arrays.copyOfRange(bytes,separator+4,bytes.length);if(body.length>256)throw error("路由响应过大");return body;
            }
        }catch(BusinessException e){throw e;}catch(Exception e){throw error("HTTPS 证书或租户路由验证失败");}finally{if(deadline!=null)deadline.cancel(false);ROUTING.release();}
    }
    // Only an operations-configured, explicitly pinned localhost route may use an alternate port.
    // Public routes keep 443; TLS chain, hostname, SNI, deadlines and response limits are unchanged.
    public String routingOrigin(String host) {
        int port=routingPort(host);
        return "https://"+host+(port==443?"":":"+port);
    }
    int routingPort(String host) {
        if(!localLoopbackEnabled)return 443;
        if(localRoutingPort<1||localRoutingPort>65535)throw error("本地路由端口配置无效");
        return localDestination(host,localRoutingPort)?localRoutingPort:443;
    }
    /** Local opt-in pins only named localhost TLS endpoints; normal TLS identity checks stay enabled. */
    private boolean localDestination(String raw,int port) {
        if(!localLoopbackEnabled||raw==null||!raw.equals(raw.trim()))return false;
        String host=raw.toLowerCase(Locale.ROOT);
        if(!host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.localhost"))return false;
        return entries(localLoopbackEndpoints).contains(host+":"+port);
    }
    private String destinationHost(String raw,int port) {
        return localDestination(raw,port)?raw.toLowerCase(Locale.ROOT):hostname(raw);
    }
    private InetAddress destinationAddress(String host,int port) {
        if(localDestination(host,port))try{return InetAddress.getByAddress(new byte[]{127,0,0,1});}catch(UnknownHostException impossible){throw new IllegalStateException(impossible);}
        return publicAddresses(host)[0];
    }
    protected InetAddress[] resolve(String host)throws UnknownHostException{return InetAddress.getAllByName(host);}
    private InetAddress[] publicAddresses(String host) {
        Future<InetAddress[]> task=null;
        try {
            task=DNS.submit(()->resolve(host));InetAddress[] all=task.get(2,TimeUnit.SECONDS);
            if(all.length==0||all.length>16)throw error("出站域名解析结果无效");
            for(InetAddress address:all)if(!publicAddress(address))throw error("出站目标必须全部解析为公开网络地址");
            return all;
        }catch(BusinessException e){throw e;}
        catch(Exception e){if(task!=null)task.cancel(true);if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw error("出站域名解析失败或超时");}
    }
    public static boolean publicAddress(InetAddress address) {
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] b=address.getAddress();int a=b[0]&255,c=b[1]&255,d=b[2]&255;
        if(b.length==4)return !(a==0||a==10||a==127||a>=224||a==100&&c>=64&&c<=127||a==169&&c==254||a==172&&c>=16&&c<=31||a==192&&(c==168||c==0||c==88&&d==99)||a==198&&(c==18||c==19||c==51&&d==100)||a==203&&c==0&&d==113);
        // Only global-unicast; reject mapped IPv4, NAT64, transition/special and documentation ranges.
        return b.length==16&&(a&224)==32&&!(a==32&&c==1&&(d<=1||d==13&&(b[3]&255)==184))&&!(a==32&&c==2);
    }
    private static String hostname(String raw){
        if(raw==null||!raw.equals(raw.trim())||raw.length()>253||raw.endsWith(".")||!raw.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"))throw error("出站主机名无效");
        String host=raw.toLowerCase(Locale.ROOT);
        if(host.indexOf('.')<0||host.matches("[0-9.]+")||host.endsWith(".localhost")||host.endsWith(".local"))throw error("出站目标必须使用公开域名，不允许 IP 或本地主机");
        for(String label:host.split("\\."))if(label.length()==0||label.length()>63||label.startsWith("-")||label.endsWith("-"))throw error("出站主机名无效");
        return host;
    }
    private static int port(String raw){try{if(raw==null||!raw.matches("[0-9]{2,5}"))throw new Exception();int p=Integer.parseInt(raw);if(p<1||p>65535)throw new Exception();return p;}catch(Exception e){throw error("SMTP 端口无效");}}
    private static Set<String> entries(String raw){Set<String>s=new HashSet<>();if(raw!=null)for(String v:raw.split(",")){String normalized=v.trim().toLowerCase(Locale.ROOT);if(normalized.endsWith(":443")&&normalized.startsWith("https://"))normalized=normalized.substring(0,normalized.length()-4);s.add(normalized);}return s;}
    private static void allow(String values,String candidate,String message){if(!entries(values).contains(candidate))throw error(message);}
    private static BusinessException error(String message){return new BusinessException(message);}
}
