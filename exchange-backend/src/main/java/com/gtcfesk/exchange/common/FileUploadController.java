package com.gtcfesk.exchange.common;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {

    private static final String UPLOAD_DIR = "uploads/";
    private static final String IMAGE_DIR = UPLOAD_DIR + "images/";
    private static final String AUDIO_DIR = UPLOAD_DIR + "audio/";
    private static final Path IMAGE_DIR_PATH;
    private static final Path AUDIO_DIR_PATH;

    static {
        // 创建上传目录（使用jar包所在目录或当前工作目录）
        Path imageDirPath;
        Path audioDirPath;
        try {
            // 尝试获取jar包所在目录
            String jarPath = FileUploadController.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath();
            
            // 解码URL编码的路径（处理空格等特殊字符）
            try {
                jarPath = URLDecoder.decode(jarPath, StandardCharsets.UTF_8.toString());
            } catch (Exception e) {
                // 解码失败，使用原始路径
            }
            
            Path basePath;
            if (jarPath != null && jarPath.endsWith(".jar")) {
                // 如果是jar包，使用jar包所在目录
                File jarFile = new File(jarPath);
                basePath = jarFile.getParentFile().toPath();
            } else {
                // 否则使用当前工作目录
                basePath = Paths.get("").toAbsolutePath();
            }
            
            // 如果basePath为null，使用当前工作目录
            if (basePath == null) {
                basePath = Paths.get("").toAbsolutePath();
            }
            
            imageDirPath = basePath.resolve(IMAGE_DIR).toAbsolutePath();
            Files.createDirectories(imageDirPath);
            System.out.println("[FileUploadController] 图片上传目录: " + imageDirPath);
            
            audioDirPath = basePath.resolve(AUDIO_DIR).toAbsolutePath();
            Files.createDirectories(audioDirPath);
            System.out.println("[FileUploadController] 音频上传目录: " + audioDirPath);
        } catch (Exception e) {
            // 如果上述方法失败，使用简单的相对路径
            try {
                imageDirPath = Paths.get(IMAGE_DIR).toAbsolutePath();
                Files.createDirectories(imageDirPath);
                System.out.println("[FileUploadController] 图片上传目录（备用）: " + imageDirPath);
                
                audioDirPath = Paths.get(AUDIO_DIR).toAbsolutePath();
                Files.createDirectories(audioDirPath);
                System.out.println("[FileUploadController] 音频上传目录（备用）: " + audioDirPath);
            } catch (IOException ex) {
                throw new RuntimeException("无法创建上传目录", ex);
            }
        }
        IMAGE_DIR_PATH = imageDirPath;
        AUDIO_DIR_PATH = audioDirPath;
    }

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

            // 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String filename = UUID.randomUUID().toString() + extension;

            // 保存文件（使用绝对路径）
            Path filePath = IMAGE_DIR_PATH.resolve(filename);
            Files.write(filePath, file.getBytes());
            
            // 验证文件是否真的保存成功
            boolean fileExists = Files.exists(filePath);
            long fileSize = fileExists ? Files.size(filePath) : 0;
            System.out.println("[FileUploadController] 文件保存: " + filePath);
            System.out.println("[FileUploadController] 文件存在: " + fileExists + ", 大小: " + fileSize + " bytes");

            // 返回文件URL，使用 /api/uploads/images/ 确保通过后端Controller处理
            // 避免被Nginx直接拦截
            String fileUrl = "/api/uploads/images/" + filename;

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("url", fileUrl);
            resp.put("message", "上传成功");
            resp.put("filePath", filePath.toString()); // 调试用
            return ResponseEntity.ok(resp);

        } catch (IOException e) {
            e.printStackTrace();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "上传失败: " + e.getMessage());
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
            String filename = UUID.randomUUID().toString() + extension;

            // 保存文件（使用绝对路径）
            Path filePath = AUDIO_DIR_PATH.resolve(filename);
            Files.write(filePath, file.getBytes());
            
            // 验证文件是否真的保存成功
            boolean fileExists = Files.exists(filePath);
            long fileSize = fileExists ? Files.size(filePath) : 0;
            System.out.println("[FileUploadController] 音频文件保存: " + filePath);
            System.out.println("[FileUploadController] 文件存在: " + fileExists + ", 大小: " + fileSize + " bytes");

            // 返回文件URL
            String fileUrl = "/api/uploads/audio/" + filename;

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("url", fileUrl);
            resp.put("message", "上传成功");
            return ResponseEntity.ok(resp);

        } catch (IOException e) {
            e.printStackTrace();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "上传失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
}

