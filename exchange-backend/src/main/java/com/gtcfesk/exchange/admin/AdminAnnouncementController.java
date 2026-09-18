package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.Announcement;
import com.gtcfesk.exchange.repository.AnnouncementRepository;
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
    public ResponseEntity<?> getAllAnnouncements() {
        List<Announcement> announcements = announcementRepository.findAllByOrderByCreatedAtDesc();
        return ResponseEntity.ok(announcements);
    }
    
    /**
     * 根据ID获取公告详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getAnnouncement(@PathVariable Long id) {
        return announcementRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * 创建公告
     */
    @PostMapping("/create")
    public ResponseEntity<?> createAnnouncement(@RequestBody AnnouncementRequest req) {
        Announcement announcement = new Announcement();
        announcement.setTitle(req.getTitle());
        announcement.setContent(req.getContent());
        announcement.setStatus(req.getStatus() != null ? req.getStatus() : "PUBLISHED");
        announcement.setPriority(req.getPriority() != null ? req.getPriority() : 0);
        announcement.setLanguage(req.getLanguage() != null && !req.getLanguage().trim().isEmpty() ? req.getLanguage() : "en");
        
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
    public ResponseEntity<?> updateAnnouncement(
            @PathVariable Long id,
            @RequestBody AnnouncementRequest req) {
        
        return announcementRepository.findById(id)
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
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteAnnouncement(@PathVariable Long id) {
        if (announcementRepository.existsById(id)) {
            announcementRepository.deleteById(id);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "公告删除成功");
            return ResponseEntity.ok(result);
        } else {
            Map<String, Object> result = new HashMap<>();
            result.put("error", "公告不存在");
            return ResponseEntity.badRequest().body(result);
        }
    }
    
    @Data
    public static class AnnouncementRequest {
        private String title;
        private String content;
        private String status; // PUBLISHED, DRAFT, HIDDEN
        private Integer priority;
        private String language; // en, zh-TW, zh-CN, fr, ja, ko, th, vi, id, es, pt, ar, tr, ru, de, it
    }
}



