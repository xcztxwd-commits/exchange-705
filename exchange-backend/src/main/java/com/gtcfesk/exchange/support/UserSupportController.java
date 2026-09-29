package com.gtcfesk.exchange.support;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController @RequestMapping("/api/user/support") @RequiredArgsConstructor
public class UserSupportController {
    private final SupportService service;
    private final SupportSettings settings;
    @org.springframework.beans.factory.annotation.Value("${support.trusted-proxies:${security.trusted-proxies:}}")
    private String trustedProxies = "";
    public static class Send { public String requestId; public String text; }
    public static class Read { public long through; }
    @GetMapping("/config") public Map<String,Object> config() { return settings.publicConfig(); }
    @GetMapping("/tones/{name}") public ResponseEntity<?> tone(@PathVariable String name) {
        if (!Arrays.asList("arrival.wav", "reply.wav").contains(name)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).cacheControl(CacheControl.maxAge(1, java.util.concurrent.TimeUnit.DAYS))
            .body(new ClassPathResource("support-tones/" + name));
    }
    @PostMapping("/sessions") public SupportConversation start(HttpServletRequest request) {
        // Only trust a forwarded address when the immediate peer belongs to an explicitly configured proxy CIDR.
        String ip = request.getRemoteAddr();
        for (String cidr : trustedProxies.split(",")) {
            if (cidr.trim().isEmpty()) continue;
            if (new org.springframework.security.web.util.matcher.IpAddressMatcher(cidr.trim()).matches(ip)) {
                String header = request.getHeader("X-Real-IP");
                if (header != null && header.matches("[0-9a-fA-F:.]{2,45}")) {
                    try { new org.springframework.security.web.util.matcher.IpAddressMatcher(header); ip = header; } catch (IllegalArgumentException ignored) { }
                }
                break;
            }
        }
        return service.start(ip);
    }
    @GetMapping("/sessions") public List<SupportConversation> sessions(@RequestParam(defaultValue = "0") int page) { return service.sessions(false, "mine", page); }
    @GetMapping("/sessions/{id}") public Map<String,Object> detail(@PathVariable long id, @RequestParam(defaultValue = "0") long after) { return service.detail(id, false, after); }
    @PostMapping("/sessions/{id}/messages") public SupportMessage send(@PathVariable long id, @RequestBody Send body) { return service.send(id, false, body.requestId, body.text, null); }
    @PostMapping("/sessions/{id}/images") public SupportMessage image(@PathVariable long id, @RequestParam String requestId, @RequestParam MultipartFile file) { return service.send(id, false, requestId, "", file); }
    @PostMapping("/sessions/{id}/read") public void read(@PathVariable long id, @RequestBody Read body) { service.read(id, false, body.through); }
    @PostMapping("/sessions/{id}/close") public void close(@PathVariable long id) { service.close(id, false); }
    @GetMapping("/images/{id}") public ResponseEntity<byte[]> attachment(@PathVariable long id) { return privateImage(service.attachment(id, false)); }
    public static ResponseEntity<byte[]> privateImage(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(bytes);
    }
    @GetMapping("/notifications") public Map<String,Object> notifications() { return service.notifications(false); }
    @GetMapping("/inbox") public List<InboxLetter> inbox(@RequestParam(defaultValue = "0") int page) { return service.inbox(false, page); }
    @PostMapping("/inbox/{id}/read") public void readLetter(@PathVariable long id) { service.readLetter(id); }
    @PostMapping("/inbox/read-all") public void readAll() { service.readAllLetters(); }
}
