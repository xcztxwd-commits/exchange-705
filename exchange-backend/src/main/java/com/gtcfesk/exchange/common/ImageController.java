package com.gtcfesk.exchange.common;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ImageController {

    private static final Path IMAGE_DIR_PATH;
    private static final Path AUDIO_DIR_PATH;

    static {
        Path imageDirPath;
        Path audioDirPath;
        try {
            // 使用与FileUploadController相同的路径逻辑
            String jarPath = ImageController.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath();
            
            // 解码URL编码的路径
            try {
                jarPath = URLDecoder.decode(jarPath, StandardCharsets.UTF_8.toString());
            } catch (Exception e) {
                // 解码失败，使用原始路径
            }
            
            Path basePath;
            if (jarPath != null && jarPath.endsWith(".jar")) {
                File jarFile = new File(jarPath);
                basePath = jarFile.getParentFile().toPath();
            } else {
                basePath = Paths.get("").toAbsolutePath();
            }
            
            if (basePath == null) {
                basePath = Paths.get("").toAbsolutePath();
            }
            
            imageDirPath = basePath.resolve("uploads/images").toAbsolutePath();
            audioDirPath = basePath.resolve("uploads/audio").toAbsolutePath();
        } catch (Exception e) {
            imageDirPath = Paths.get("uploads/images").toAbsolutePath();
            audioDirPath = Paths.get("uploads/audio").toAbsolutePath();
        }
        IMAGE_DIR_PATH = imageDirPath;
        AUDIO_DIR_PATH = audioDirPath;
        System.out.println("[ImageController] 图片目录初始化: " + IMAGE_DIR_PATH);
        System.out.println("[ImageController] 音频目录初始化: " + AUDIO_DIR_PATH);
    }

    // 支持两种路径：/uploads/images/ 和 /api/uploads/images/
    // 使用 ** 通配符匹配所有路径，包括文件名中的点
    @GetMapping({"/uploads/images/**", "/api/uploads/images/**", "/uploads/audio/**", "/api/uploads/audio/**"})
    public ResponseEntity<Resource> getFile(HttpServletRequest request) {
        // 从请求路径中提取文件名
        String requestURI = request.getRequestURI();
        boolean isAudio = requestURI.contains("/audio/");
        System.out.println("[ImageController] 收到文件请求，完整URI: " + requestURI + ", 类型: " + (isAudio ? "音频" : "图片"));
        
        // 提取文件名部分
        String filename = null;
        Path baseDir;
        if (requestURI.contains("/api/uploads/images/")) {
            filename = requestURI.substring(requestURI.indexOf("/api/uploads/images/") + "/api/uploads/images/".length());
            baseDir = IMAGE_DIR_PATH;
        } else if (requestURI.contains("/uploads/images/")) {
            filename = requestURI.substring(requestURI.indexOf("/uploads/images/") + "/uploads/images/".length());
            baseDir = IMAGE_DIR_PATH;
        } else if (requestURI.contains("/api/uploads/audio/")) {
            filename = requestURI.substring(requestURI.indexOf("/api/uploads/audio/") + "/api/uploads/audio/".length());
            baseDir = AUDIO_DIR_PATH;
        } else if (requestURI.contains("/uploads/audio/")) {
            filename = requestURI.substring(requestURI.indexOf("/uploads/audio/") + "/uploads/audio/".length());
            baseDir = AUDIO_DIR_PATH;
        } else {
            return ResponseEntity.notFound().build();
        }
        
        // URL 解码文件名（处理特殊字符）
        if (filename != null) {
            try {
                filename = URLDecoder.decode(filename, StandardCharsets.UTF_8.toString());
            } catch (Exception e) {
                System.out.println("[ImageController] URL解码失败: " + e.getMessage());
            }
        }
        
        System.out.println("[ImageController] 提取的文件名: " + filename);
        System.out.println("[ImageController] 文件目录: " + baseDir);
        
        if (filename == null || filename.isEmpty()) {
            System.out.println("[ImageController] 错误: 无法提取文件名");
            return ResponseEntity.notFound().build();
        }
        try {
            Path filePath = baseDir.resolve(filename).normalize();
            System.out.println("[ImageController] 完整文件路径: " + filePath);
            
            // 安全检查：确保文件在允许的目录下
            if (!filePath.startsWith(baseDir)) {
                System.out.println("[ImageController] 安全检查失败: 文件路径不在允许的目录内");
                return ResponseEntity.notFound().build();
            }
            
            File file = filePath.toFile();
            System.out.println("[ImageController] 文件存在: " + file.exists() + ", 是文件: " + file.isFile());
            if (!file.exists() || !file.isFile()) {
                System.out.println("[ImageController] 文件不存在: " + filePath);
                return ResponseEntity.notFound().build();
            }
            
            Resource resource = new FileSystemResource(file);
            
            // 确定Content-Type
            String contentType = "application/octet-stream";
            try {
                contentType = Files.probeContentType(filePath);
                if (contentType == null) {
                    String lowerFilename = filename.toLowerCase();
                    if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg")) {
                        contentType = "image/jpeg";
                    } else if (lowerFilename.endsWith(".png")) {
                        contentType = "image/png";
                    } else if (lowerFilename.endsWith(".gif")) {
                        contentType = "image/gif";
                    } else if (lowerFilename.endsWith(".webp")) {
                        contentType = "image/webp";
                    } else if (lowerFilename.endsWith(".mp3")) {
                        contentType = "audio/mpeg";
                    } else if (lowerFilename.endsWith(".wav")) {
                        contentType = "audio/wav";
                    } else if (lowerFilename.endsWith(".ogg")) {
                        contentType = "audio/ogg";
                    }
                }
            } catch (IOException e) {
                // 使用默认类型
            }
            
            System.out.println("[ImageController] 成功返回文件: " + filename + ", Content-Type: " + contentType);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .body(resource);
                    
        } catch (Exception e) {
            System.out.println("[ImageController] 错误: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.notFound().build();
        }
    }
    
    // 简单测试接口：验证后端是否可访问
    @GetMapping("/api/uploads/images/test/ping")
    public ResponseEntity<?> ping() {
        System.out.println("[ImageController] Ping 接口被调用！");
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("message", "ImageController is working");
        result.put("imageDirectory", IMAGE_DIR_PATH.toString());
        result.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(result);
    }
    
    // 测试接口：检查图片是否存在
    @GetMapping("/api/uploads/images/test/check/{filename:.+}")
    public ResponseEntity<?> checkImage(@PathVariable String filename) {
        System.out.println("[ImageController] 测试接口被调用，检查文件: " + filename);
        try {
            Path filePath = IMAGE_DIR_PATH.resolve(filename).normalize();
            File file = filePath.toFile();
            
            Map<String, Object> result = new HashMap<>();
            result.put("filename", filename);
            result.put("directory", IMAGE_DIR_PATH.toString());
            result.put("fullPath", filePath.toString());
            result.put("exists", file.exists());
            result.put("isFile", file.isFile());
            result.put("size", file.exists() ? file.length() : 0);
            result.put("url", "/api/uploads/images/" + filename);
            
            if (file.exists()) {
                result.put("status", "文件存在");
            } else {
                result.put("status", "文件不存在");
                // 列出目录内容
                File dir = IMAGE_DIR_PATH.toFile();
                if (dir.exists() && dir.isDirectory()) {
                    String[] files = dir.list();
                    result.put("directoryFiles", files != null ? java.util.Arrays.asList(files) : Collections.emptyList());
                }
            }
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", com.gtcfesk.exchange.common.SafeErrors.message(e));
            error.put("stackTrace", e.getStackTrace());
            return ResponseEntity.ok(error);
        }
    }
    
    // 测试接口：列出所有图片文件
    @GetMapping("/api/uploads/images/test/list")
    public ResponseEntity<?> listImages() {
        try {
            File dir = IMAGE_DIR_PATH.toFile();
            if (!dir.exists() || !dir.isDirectory()) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "目录不存在: " + IMAGE_DIR_PATH);
                return ResponseEntity.ok(error);
            }
            
            String[] files = dir.list();
            if (files == null) {
                Map<String, Object> result = new HashMap<>();
                result.put("files", Collections.emptyList());
                return ResponseEntity.ok(result);
            }
            
            List<Map<String, Object>> fileList = new ArrayList<>();
            for (String file : files) {
                File f = new File(dir, file);
                if (f.isFile()) {
                    Map<String, Object> fileInfo = new HashMap<>();
                    fileInfo.put("name", file);
                    fileInfo.put("size", f.length());
                    fileInfo.put("url", "/api/uploads/images/" + file);
                    fileList.add(fileInfo);
                }
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("directory", IMAGE_DIR_PATH.toString());
            result.put("count", fileList.size());
            result.put("files", fileList);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.ok(error);
        }
    }
}

