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
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.config.BackendAccess access;
    @org.springframework.beans.factory.annotation.Autowired private PublishedTenantFiles published;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService audit;

    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.common.UploadStorage storage;

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
            baseDir = storage.images();
        } else if (requestURI.contains("/uploads/images/")) {
            filename = requestURI.substring(requestURI.indexOf("/uploads/images/") + "/uploads/images/".length());
            baseDir = storage.images();
        } else if (requestURI.contains("/api/uploads/audio/")) {
            filename = requestURI.substring(requestURI.indexOf("/api/uploads/audio/") + "/api/uploads/audio/".length());
            baseDir = storage.audio();
        } else if (requestURI.contains("/uploads/audio/")) {
            filename = requestURI.substring(requestURI.indexOf("/uploads/audio/") + "/uploads/audio/".length());
            baseDir = storage.audio();
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
        Long tenant=com.gtcfesk.exchange.tenant.TenantContext.currentTenantId();
        if(tenant==null)return ResponseEntity.status(401).build();
        String[] parts=filename.split("/");
        if(parts.length!=4 || !parts[0].equals(tenant.toString()) || !parts[2].matches("(?:agent-)?-?[0-9]{1,19}") || !parts[3].matches("[a-zA-Z0-9_.-]+"))return ResponseEntity.notFound().build();
        org.springframework.security.core.Authentication auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean user=auth!=null&&auth.isAuthenticated()&&auth.getAuthorities().stream().anyMatch(a->"ROLE_USER".equals(a.getAuthority()));
        boolean backend=auth!=null&&auth.isAuthenticated()&&auth.getAuthorities().stream().anyMatch(a->java.util.Arrays.asList("ROLE_ADMIN","ROLE_SUPER_ADMIN","ROLE_AGENT").contains(a.getAuthority()));
        boolean publicReference=published.allows(filename,isAudio,user||backend);
        if(!publicReference){
            if(user){if(!"user".equals(parts[1])||!parts[2].equals(auth.getName()))return ResponseEntity.notFound().build();}
            else if(backend){
                boolean ownStaff="staff".equals(parts[1])&&parts[2].equals(auth.getName());
                if("user".equals(parts[1])){
                    if(!parts[2].matches("[0-9]{1,19}"))return ResponseEntity.notFound().build();
                    Long owner;try{owner=Long.valueOf(parts[2]);}catch(NumberFormatException e){return ResponseEntity.notFound().build();}
                    access.checkUser(owner);
                    java.util.Set<String> modules=new java.util.HashSet<>();for(String module:java.util.Arrays.asList("kyc_review","loan_personal_info_review","loan_review","deposit_review","deposit_orders"))if(access.canReadMenu(module))modules.add(module);
                    boolean avatar=!isAudio&&access.canReadMenu("users")&&published.allowsUserAvatar(filename,owner);
                    if(isAudio||(!avatar&&!published.allowsReview(filename,owner,modules)))return ResponseEntity.notFound().build();
                }else if(!ownStaff){
                    boolean materialReader=!isAudio&&auth.getAuthorities().stream().anyMatch(a->java.util.Arrays.asList("ROLE_ADMIN","ROLE_SUPER_ADMIN").contains(a.getAuthority()))&&((access.canReadMenu("announcement")&&published.allowsMaterial(filename))||(access.canReadMenu("share_templates")&&published.allowsShare(filename,false))||(access.canReadMenu("traders")&&published.allowsTraderAvatar(filename,false)));
                    if(!materialReader)return ResponseEntity.notFound().build();
                }
            }else return ResponseEntity.status(401).build();
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
            
            Path realBase=baseDir.toRealPath(), realFile=filePath.toRealPath();
            if(!realFile.startsWith(realBase.resolve(parts[0]).resolve(parts[1]).resolve(parts[2])))return ResponseEntity.notFound().build();
            Resource resource = new FileSystemResource(realFile);
            
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
            
            if(isAudio&&!java.util.Arrays.asList("audio/mpeg","audio/mp3","audio/wav","audio/x-wav","audio/ogg","application/ogg").contains(contentType))return ResponseEntity.notFound().build();
            if(!isAudio&&!java.util.Arrays.asList("image/jpeg","image/png","image/gif","image/webp").contains(contentType))return ResponseEntity.notFound().build();
            audit.recordCurrent("file.access.granted",filename,isAudio?"audio":"image",null);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .body(resource);
                    
        } catch (Exception e) {
            System.out.println("[ImageController] 错误: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.notFound().build();
        }
    }
    
}
