package com.gtcfesk.exchange.support;

import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.admin.AdminPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController @RequestMapping("/api/admin/support") @RequiredArgsConstructor
public class AdminSupportController {
    private final SupportService service;
    private final SupportSettings settings;
    private final AdminPermissionService permissions;
    public static class Presence { public boolean accepting; }
    public static class Transfer { public Long target; }
    public static class Letter { public String requestId; public List<Long> users; public String title; public String content; }
    @GetMapping("/settings") @AdminPermission(menu="support_settings", action="")
    public SupportSettings.Settings config() { service.subject(true); return settings.get(); }
    @PostMapping("/settings") @AdminPermission(menu="support_settings", action="save")
    public void save(@RequestBody SupportSettings.Settings body) { service.subject(true); settings.save(body); }
    @GetMapping("/sessions") @AdminPermission(menu="support", action="")
    public List<SupportConversation> sessions(@RequestParam(defaultValue="mine") String scope, @RequestParam(defaultValue="0") int page) { return service.sessions(true, scope, page); }
    @GetMapping("/sessions/{id}") @AdminPermission(menu="support", action="detail")
    public Map<String,Object> detail(@PathVariable long id, @RequestParam(defaultValue="0") long after) { return service.detail(id, true, after); }
    @PostMapping("/presence") @AdminPermission(menu="support", action="claim")
    public SupportPresence presence(@RequestBody Presence body) { return service.presence(body.accepting); }
    @GetMapping("/agents") @AdminPermission(menu="support", action="transfer")
    public List<Map<String,Object>> agents() { service.subject(true); return service.onlineAgents(); }
    @PostMapping("/sessions/{id}/claim") @AdminPermission(menu="support", action="claim")
    public SupportConversation claim(@PathVariable long id) { return service.claim(id); }
    @PostMapping("/sessions/{id}/transfer") @AdminPermission(menu="support", action="transfer")
    public SupportConversation transfer(@PathVariable long id, @RequestBody Transfer body) { return service.transfer(id, body.target); }
    @PostMapping("/sessions/{id}/close") @AdminPermission(menu="support", action="close")
    public void close(@PathVariable long id) { service.close(id, true); }
    @PostMapping("/sessions/{id}/messages") @AdminPermission(menu="support", action="reply")
    public SupportMessage send(@PathVariable long id, @RequestBody UserSupportController.Send body) { return service.send(id, true, body.requestId, body.text, null); }
    @PostMapping("/sessions/{id}/images") @AdminPermission(menu="support", action="image")
    public SupportMessage image(@PathVariable long id, @RequestParam String requestId, @RequestParam MultipartFile file) {
        permissions.require("support", "reply"); return service.send(id, true, requestId, "", file);
    }
    @GetMapping("/images/{id}") @AdminPermission(menu="support", action="image")
    public ResponseEntity<byte[]> image(@PathVariable long id) { return UserSupportController.privateImage(service.attachment(id, true)); }
    @PostMapping("/sessions/{id}/read") @AdminPermission(menu="support", action="detail")
    public void read(@PathVariable long id, @RequestBody UserSupportController.Read body) { service.read(id, true, body.through); }
    @GetMapping("/notifications") @AdminPermission(menu="support", action="")
    public Map<String,Object> notifications() { return service.notifications(true); }
    @GetMapping("/sessions/{id}/export") @AdminPermission(menu="support", action="export")
    public ResponseEntity<?> export(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Content-Disposition", "attachment; filename=conversation-"+id+".json").body(service.export(id));
    }
    @GetMapping("/inbox/recipients") @AdminPermission(menu="inbox", action="send")
    public List<Map<String,Object>> recipients(@RequestParam String query) { return service.searchRecipients(query); }
    @GetMapping("/inbox") @AdminPermission(menu="inbox", action="")
    public List<InboxLetter> inbox(@RequestParam(defaultValue="0") int page) { return service.inbox(true, page); }
    @PostMapping("/inbox") @AdminPermission(menu="inbox", action="send")
    public Map<String,Integer> sendLetters(@RequestBody Letter body) { return Collections.singletonMap("sent", service.sendLetters(body.requestId, body.users, body.title, body.content)); }
}
