package com.gtcfesk.exchange.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.gtcfesk.exchange.admin.AdminPermissionService;
import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.admin.VideoIntroSettings;
import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.control.TenantPolicyService;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantFiles;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpRange;
import org.springframework.security.core.Authentication;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class VideoController {
    public static final long MAX_VIDEO_SIZE = 100L * 1024 * 1024;
    private final MinioObjectStorage storage;
    private final SystemConfigService configs;
    private final AdminPermissionService permissions;
    private final TenantPolicyService policy;

    @PostMapping("/api/admin/videos/upload")
    @AdminPermission(menu = "settings", action = "save")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws Exception {
        permissions.require("settings", "save");
        policy.requireConfigChange(VideoIntroSettings.KEY, null);
        if (file.isEmpty()) throw new BusinessException("文件为空");
        if (file.getSize() > MAX_VIDEO_SIZE) throw new BusinessException("视频大小不能超过100MB");
        String name = file.getOriginalFilename();
        String extension = name == null || !name.contains(".") ? "" : name.substring(name.lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT);
        String contentType = ".mp4".equals(extension) ? "video/mp4" : ".webm".equals(extension) ? "video/webm" : "";
        try (InputStream stream = file.getInputStream()) {
            byte[] header = new byte[12];
            int read = 0, next;
            while (read < header.length && (next = stream.read(header, read, header.length - read)) > 0) read += next;
            boolean valid = read == 12 && (".mp4".equals(extension)
                    ? header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p'
                    : ".webm".equals(extension) && (header[0] & 255) == 0x1a && (header[1] & 255) == 0x45
                        && (header[2] & 255) == 0xdf && (header[3] & 255) == 0xa3);
            if (!valid || !contentType.equals(file.getContentType())) throw new BusinessException("仅支持有效 MP4 或 WebM 视频");
        }
        String filename = TenantFiles.ownerPath() + "/" + UUID.randomUUID() + extension;
        try (InputStream stream = file.getInputStream()) {
            storage.put("videos/" + filename, stream, file.getSize(), contentType);
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException("视频上传至 MinIO 失败，请检查存储连接后重试"); }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("url", VideoIntroSettings.URL_PREFIX + filename);
        return result;
    }

    @GetMapping("/api/user/video-intro")
    public Map<String, String> introduction(@RequestParam(required = false) String language) {
        String value = configs.getConfigValue(VideoIntroSettings.KEY);
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null) {
            String legacy = configs.getConfigValue(SystemConfigService.VIDEO_INTRO_URL_KEY);
            result.put("url", legacy == null ? "" : legacy);
        } else {
            JsonNode settings = VideoIntroSettings.parse(value, TenantContext.requireTenantId());
            String selected = VideoIntroSettings.language(settings, language);
            result.put("url", settings.path("videos").path(selected).asText(""));
            result.put("language", selected);
            result.put("defaultLocale", settings.path("defaultLocale").asText());
        }
        return result;
    }

    @RequestMapping(value = "/api/uploads/videos/**", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void video(HttpServletRequest request, HttpServletResponse response, Authentication auth) throws Exception {
        String url = request.getRequestURI();
        Long tenant = TenantContext.currentTenantId();
        if (tenant == null || !VideoIntroSettings.videoPath(url, tenant)) { response.setStatus(404); return; }
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        boolean published = VideoIntroSettings.published(configs.getConfigValue(VideoIntroSettings.KEY), url, tenant);
        if (!published) {
            if (auth == null || !auth.isAuthenticated()) { response.setStatus(404); return; }
            try { permissions.require("settings", ""); }
            catch (org.springframework.security.access.AccessDeniedException e) { response.setStatus(404); return; }
            if (!url.startsWith(VideoIntroSettings.URL_PREFIX + TenantFiles.ownerPath() + "/")) { response.setStatus(404); return; }
        }
        String object = "videos/" + url.substring(VideoIntroSettings.URL_PREFIX.length());
        StatObjectResponse info;
        try { info = storage.stat(object); }
        catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) { response.setStatus(404); return; }
            throw new BusinessException("视频存储暂时不可用");
        }
        catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException("视频存储暂时不可用"); }
        long start = 0, end = info.size() - 1;
        String range = request.getHeader("Range");
        if (range != null) {
            try {
                java.util.List<HttpRange> ranges = HttpRange.parseRanges(range);
                if (ranges.size() != 1) throw new IllegalArgumentException();
                start = ranges.get(0).getRangeStart(info.size());
                end = ranges.get(0).getRangeEnd(info.size());
                if (start < 0 || start > end || end >= info.size()) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                response.setStatus(416); response.setHeader("Content-Range", "bytes */" + info.size()); return;
            }
            response.setStatus(206);
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + info.size());
        }
        response.setHeader("Accept-Ranges", "bytes");
        response.setContentType(url.endsWith(".mp4") ? "video/mp4" : "video/webm");
        response.setContentLengthLong(end - start + 1);
        if (!"HEAD".equals(request.getMethod())) {
            try (InputStream stream = storage.read(object, start, end - start + 1)) {
                StreamUtils.copy(stream, response.getOutputStream());
            }
        }
    }
}
