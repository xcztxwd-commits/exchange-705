package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.Announcement;
import com.gtcfesk.exchange.repository.AnnouncementRepository;
import javax.validation.Valid;
import javax.validation.constraints.Min;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/announcement")
@RequiredArgsConstructor
public class AdminAnnouncementController {
    
    private final AnnouncementRepository announcementRepository;
    
    /**
     * 获取所有公告列表（包括草稿和隐藏的）
     */
    @GetMapping("/list")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "announcement", action = "")
    public ResponseEntity<?> getAllAnnouncements() {
        List<Announcement> announcements = announcementRepository.findAllByTenantIdOrderByCreatedAtDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        return ResponseEntity.ok(announcements);
    }
    
    /**
     * 根据ID获取公告详情
     */
    @GetMapping("/{id}")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "announcement", action = "detail")
    public ResponseEntity<?> getAnnouncement(@PathVariable Long id) {
        return announcementRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * 创建公告
     */
    @PostMapping("/create")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "announcement", action = "create")
    public ResponseEntity<?> createAnnouncement(@Valid @RequestBody AnnouncementRequest req) {
        Announcement announcement = new Announcement();
        announcement.setTitle(req.getTitle());
        announcement.setContent(req.getContent());
        announcement.setDisplayAt(req.getDisplayAt());
        announcement.setStatus(req.getStatus() != null ? req.getStatus() : "PUBLISHED");
        announcement.setPriority(req.getPriority() != null ? req.getPriority() : 0);
        announcement.setLanguage(req.getLanguage() != null && !req.getLanguage().trim().isEmpty() ? req.getLanguage() : "en");
        
        if (req.getCountdownSeconds() != null) announcement.setCountdownSeconds(req.getCountdownSeconds());

        Announcement saved = announcementRepository.save(announcement);
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "公告创建成功");
        result.put("id", saved.getId());
        return ResponseEntity.ok(result);
    }
    
    /**
     * 更新公告
     */
    @PutMapping("/{id}")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "announcement", action = "edit")
    public ResponseEntity<?> updateAnnouncement(
            @PathVariable Long id,
            @Valid @RequestBody AnnouncementRequest req) {
        
        return announcementRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id)
                .map(announcement -> {
                    if (req.getTitle() != null) {
                        announcement.setTitle(req.getTitle());
                    }
                    if (req.getContent() != null) {
                        announcement.setContent(req.getContent());
                    }
                    if (req.getStatus() != null) {
                        announcement.setStatus(req.getStatus());
                    }
                    if (req.getPriority() != null) {
                        announcement.setPriority(req.getPriority());
                    }
                    if (req.getLanguage() != null && !req.getLanguage().trim().isEmpty()) {
                        announcement.setLanguage(req.getLanguage());
                    }
                    
                    if (req.getCountdownSeconds() != null) {
                        announcement.setCountdownSeconds(req.getCountdownSeconds());
                    }
                    if (req.getDisplayAt() != null) announcement.setDisplayAt(req.getDisplayAt());
                    announcementRepository.save(announcement);
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("message", "公告更新成功");
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * 删除公告
     */
    @org.springframework.transaction.annotation.Transactional
    @DeleteMapping("/{id}")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "announcement", action = "delete")
    public ResponseEntity<?> deleteAnnouncement(@PathVariable Long id) {
        java.util.Optional<Announcement> existing = announcementRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id);
        if (existing.isPresent()) {
            // Published history and read receipts remain linked; public queries already require PUBLISHED.
            Announcement announcement = existing.get();
            announcement.setStatus("HIDDEN");
            announcementRepository.saveAndFlush(announcement);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "公告已下架，历史及已读记录保留");
            return ResponseEntity.ok(result);
        } else {
            Map<String, Object> result = new HashMap<>();
            result.put("error", "公告不存在");
            return ResponseEntity.badRequest().body(result);
        }
    }
    
    @Data
    public static class AnnouncementRequest {
        private java.time.LocalDateTime displayAt;
        private String title;
        private String content;
        private String status; // PUBLISHED, DRAFT, HIDDEN
        @Min(value = 0, message = "倒计时不能小于0秒")
        private Integer countdownSeconds;
        private Integer priority;
        private String language; // en, zh-TW, zh-CN, fr, ja, ko, th, vi, id, es, pt, ar, tr, ru, de, it
    }
}



