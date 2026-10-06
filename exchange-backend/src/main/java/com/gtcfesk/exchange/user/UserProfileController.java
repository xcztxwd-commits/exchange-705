package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared real identity, independent of the selected trading account mode. */
@RestController
@RequestMapping("/api/user/profile")
@RequiredArgsConstructor
public class UserProfileController {
    private final UserAccountRepository users;
    private final FileUploadService uploads;

    @GetMapping
    public ResponseEntity<?> get(Authentication auth) {
        return response(ownUser(auth));
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<?> update(Authentication auth,
            @RequestParam String nickname,
            @RequestParam(defaultValue = "false") boolean removeAvatar,
            @RequestParam(required = false) MultipartFile avatar) {
        UserAccount user = ownUser(auth);
        String value = nickname.replaceAll("(?U)^\\s+|\\s+$", "");
        if (value.codePointCount(0, value.length()) > 50 || value.codePoints().anyMatch(Character::isISOControl))
            throw new BusinessException("昵称最多50个字符，不能包含控制字符");
        if (removeAvatar && avatar != null) throw new BusinessException("不能同时上传和移除头像");
        // Only a decoded upload owned by this authenticated user can become an avatar. No URL binding.
        String url = avatar != null ? uploads.uploadImage(avatar) : removeAvatar ? null : user.getAvatarUrl();
        user.setNickname(value.isEmpty() ? null : value);
        user.setAvatarUrl(url);
        users.saveAndFlush(user);
        return response(user);
    }

    private UserAccount ownUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))
                || !auth.getName().matches("[0-9]{1,19}")) throw new AccessDeniedException("请先登录用户账户");
        Long id;
        try { id = Long.valueOf(auth.getName()); } catch (NumberFormatException e) { throw new AccessDeniedException("身份无效"); }
        return users.findByTenantIdAndId(TenantContext.requireTenantId(), id)
                .orElseThrow(() -> new AccessDeniedException("用户不存在"));
    }

    private ResponseEntity<?> response(UserAccount user) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", user.getId());
        profile.put("email", user.getEmail());
        profile.put("nickname", user.getNickname());
        profile.put("avatarUrl", user.getAvatarUrl());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profile);
    }
}
