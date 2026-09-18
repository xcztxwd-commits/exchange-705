package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;

@RestController
@RequestMapping("/api/user/invite")
@RequiredArgsConstructor
public class InviteController {

    private final UserAccountRepository userAccountRepository;

    /**
     * 获取当前用户的邀请信息
     */
    @GetMapping("/info")
    public ResponseEntity<?> getInviteInfo(Authentication auth) {
        if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "用户未登录");
            return ResponseEntity.status(401).body(resp);
        }

        Long userId = Long.parseLong(auth.getName());
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // 如果用户还没有自己的邀请码，生成一个
        if (user.getMyInviteCode() == null || user.getMyInviteCode().isEmpty()) {
            String inviteCode = generateInviteCode(userId);
            user.setMyInviteCode(inviteCode);
            userAccountRepository.save(user);
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("inviteCode", user.getMyInviteCode());
        return ResponseEntity.ok(resp);
    }

    /**
     * 获取当前用户的下级用户列表
     */
    @GetMapping("/subordinates")
    public ResponseEntity<?> getSubordinates(Authentication auth) {
        if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "用户未登录");
            return ResponseEntity.status(401).body(resp);
        }

        Long userId = Long.parseLong(auth.getName());
        List<UserAccount> subordinates = userAccountRepository.findByParentUserId(userId);

        List<Map<String, Object>> list = subordinates.stream().map(u -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", u.getId());
            item.put("email", u.getEmail());
            item.put("nickname", u.getNickname());
            item.put("status", u.getStatus());
            item.put("createdAt", u.getCreatedAt());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> resp = new HashMap<>();
        resp.put("list", list);
        resp.put("total", list.size());
        return ResponseEntity.ok(resp);
    }

    /**
     * 生成邀请码（6位大写字母+数字）
     */
    private String generateInviteCode(Long userId) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 排除容易混淆的字符
        StringBuilder code = new StringBuilder();
        
        // 基于用户ID生成唯一邀请码
        long seed = userId * 31 + System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            code.append(chars.charAt((int) (Math.abs(seed) % chars.length())));
            seed = seed / chars.length() + i;
        }
        
        // 确保唯一性
        String baseCode = code.toString();
        String finalCode = baseCode;
        int suffix = 0;
        while (userAccountRepository.findByMyInviteCode(finalCode).isPresent()) {
            suffix++;
            finalCode = baseCode.substring(0, 5) + chars.charAt(suffix % chars.length());
        }
        
        return finalCode;
    }
}

