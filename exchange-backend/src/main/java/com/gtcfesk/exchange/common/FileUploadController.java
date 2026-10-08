package com.gtcfesk.exchange.common;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.simulation.SimulationEnvironment simulation;

    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.common.UploadStorage storage;

    @PostMapping("/image")
    public ResponseEntity<?> uploadImage(
            Authentication auth,
            @RequestParam("file") MultipartFile file) {
        try {
            if (auth == null) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "未登录");
                return ResponseEntity.status(401).body(resp);
            }

            if (file.isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "文件为空");
                return ResponseEntity.badRequest().body(resp);
            }

            // 检查文件类型
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "只能上传图片文件");
                return ResponseEntity.badRequest().body(resp);
            }

            // 检查文件大小（5MB）
            if (file.getSize() > 5 * 1024 * 1024) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "图片大小不能超过5MB");
                return ResponseEntity.badRequest().body(resp);
            }

            String filename = storage.saveImage(file);

            // 返回文件URL，使用 /api/uploads/images/ 确保通过后端Controller处理
            // 避免被Nginx直接拦截
            String fileUrl = (simulation != null && simulation.enabled() ? "/demo-uploads/images/" : "/api/uploads/images/") + filename;

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("url", fileUrl);
            resp.put("message", "上传成功");

            return ResponseEntity.ok(resp);

        } catch (IOException e) {
            e.printStackTrace();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "上传失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @PostMapping("/audio")
    public ResponseEntity<?> uploadAudio(
            Authentication auth,
            @RequestParam("file") MultipartFile file) {
        try {
            if (auth == null) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "未登录");
                return ResponseEntity.status(401).body(resp);
            }

            if (file.isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "文件为空");
                return ResponseEntity.badRequest().body(resp);
            }

            // 检查文件类型
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("audio/")) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "只能上传音频文件");
                return ResponseEntity.badRequest().body(resp);
            }

            // 检查文件大小（5MB）
            if (file.getSize() > 5 * 1024 * 1024) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "音频文件大小不能超过5MB");
                return ResponseEntity.badRequest().body(resp);
            }

            // 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            extension=extension.toLowerCase(java.util.Locale.ROOT);
            byte[] audio=file.getBytes();
            if(!validAudio(extension,audio))return ResponseEntity.badRequest().body(java.util.Collections.singletonMap("message","仅支持有效 MP3、WAV 或 OGG 音频"));
            String filename = UUID.randomUUID().toString() + extension;

            // 保存文件（使用绝对路径）
            filename = com.gtcfesk.exchange.tenant.TenantFiles.ownerPath()+"/"+filename;
            storage.save("audio", filename, audio, ".wav".equals(extension) ? "audio/wav" : ".ogg".equals(extension) ? "audio/ogg" : "audio/mpeg");

            // 返回文件URL
            String fileUrl = (simulation != null && simulation.enabled() ? "/demo-uploads/audio/" : "/api/uploads/audio/") + filename;

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("url", fileUrl);
            resp.put("message", "上传成功");
            return ResponseEntity.ok(resp);

        } catch (IOException e) {
            e.printStackTrace();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "上传失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    static boolean validAudio(String extension,byte[] audio){
        if(audio==null||audio.length<12)return false;
        if(".wav".equals(extension))return audio[0]=='R'&&audio[1]=='I'&&audio[2]=='F'&&audio[3]=='F'&&audio[8]=='W'&&audio[9]=='A'&&audio[10]=='V'&&audio[11]=='E';
        if(".ogg".equals(extension))return audio[0]=='O'&&audio[1]=='g'&&audio[2]=='g'&&audio[3]=='S';
        return ".mp3".equals(extension)&&((audio[0]=='I'&&audio[1]=='D'&&audio[2]=='3')||((audio[0]&255)==255&&(audio[1]&224)==224));
    }
}
