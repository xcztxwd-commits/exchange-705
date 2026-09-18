package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.Announcement;
import com.gtcfesk.exchange.repository.AnnouncementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class AnnouncementController {
    
    private final AnnouncementRepository announcementRepository;
    
    /**
     * 获取所有已发布的公告列表（支持按语言过滤）
     * @param language 可选的语言参数，如：en, zh-TW, zh-CN, fr, ja, ko, th, vi, id, es, pt, ar, tr, ru, de, it
     */
    @GetMapping("/announcements")
    public ResponseEntity<?> getAnnouncements(@RequestParam(required = false) String language) {
        List<Announcement> announcements;
        
        // 如果提供了语言参数，按语言过滤；否则返回所有语言的公告
        if (language != null && !language.trim().isEmpty()) {
            announcements = announcementRepository.findAllPublishedByLanguage(language.trim());
        } else {
            announcements = announcementRepository.findAllPublished();
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("announcements", announcements);
        result.put("count", announcements.size());
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取最新的公告（支持按语言过滤）
     * @param language 可选的语言参数，如：en, zh-TW, zh-CN, fr, ja, ko, th, vi, id, es, pt, ar, tr, ru, de, it
     */
    @GetMapping("/announcements/latest")
    public ResponseEntity<?> getLatestAnnouncement(@RequestParam(required = false) String language) {
        Optional<Announcement> announcementOpt;
        
        // 如果提供了语言参数，按语言过滤；否则返回所有语言的最新公告
        if (language != null && !language.trim().isEmpty()) {
            announcementOpt = announcementRepository.findLatestPublishedByLanguage(language.trim());
        } else {
            announcementOpt = announcementRepository.findLatestPublished();
        }
        
        return announcementOpt
                .map(announcement -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("announcement", announcement);
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.ok(new HashMap<>()));
    }
    
    /**
     * 根据ID获取公告详情
     */
    @GetMapping("/announcements/{id}")
    public ResponseEntity<?> getAnnouncement(@PathVariable Long id) {
        return announcementRepository.findById(id)
                .map(announcement -> {
                    // 只返回已发布的公告
                    if (!"PUBLISHED".equals(announcement.getStatus())) {
                        Map<String, Object> error = new HashMap<>();
                        error.put("error", "公告不存在或已下架");
                        return ResponseEntity.badRequest().body(error);
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("announcement", announcement);
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}



