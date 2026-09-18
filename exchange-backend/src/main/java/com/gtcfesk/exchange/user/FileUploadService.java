package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.Base64MultipartFile;
import com.gtcfesk.exchange.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.UUID;

@Service
public class FileUploadService {
    
    private static final String IMAGE_DIR = "uploads/images/";
    private static final Path IMAGE_DIR_PATH;
    
    static {
        Path imageDirPath;
        try {
            // 使用与FileUploadController相同的路径逻辑
            // 尝试获取jar包所在目录
            String jarPath = FileUploadService.class.getProtectionDomain()
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
            System.out.println("[FileUploadService] 图片上传目录: " + imageDirPath);
        } catch (Exception e) {
            // 如果上述方法失败，使用简单的相对路径
            try {
                imageDirPath = Paths.get(IMAGE_DIR).toAbsolutePath();
                Files.createDirectories(imageDirPath);
                System.out.println("[FileUploadService] 图片上传目录（备用）: " + imageDirPath);
            } catch (IOException ex) {
                throw new RuntimeException("无法创建上传目录: " + IMAGE_DIR, ex);
            }
        }
        IMAGE_DIR_PATH = imageDirPath;
    }
    
    /**
     * 上传base64格式的图片
     */
    public String uploadBase64Image(String base64Image) {
        try {
            // 解析base64数据
            String[] parts = base64Image.split(",");
            if (parts.length != 2) {
                throw new BusinessException("无效的base64图片格式");
            }
            
            String base64Data = parts[1];
            String mimeType = parts[0].replace("data:", "").replace(";base64", "");
            
            // 确定文件扩展名
            String extension = "png";
            if (mimeType.contains("jpeg") || mimeType.contains("jpg")) {
                extension = "jpg";
            } else if (mimeType.contains("gif")) {
                extension = "gif";
            }
            
            // 解码base64
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);
            
            // 创建临时MultipartFile
            String fileName = "signature_" + System.currentTimeMillis() + "." + extension;
            MultipartFile multipartFile = new Base64MultipartFile(imageBytes, fileName, mimeType);
            
            return uploadImage(multipartFile);
        } catch (Exception e) {
            throw new BusinessException("上传签名图片失败: " + e.getMessage());
        }
    }

    public String uploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件为空");
        }
        
        // 检查文件类型
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("只能上传图片文件");
        }
        
        // 检查文件大小（5MB）
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BusinessException("图片大小不能超过5MB");
        }
        
        try {
            // 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String filename = UUID.randomUUID().toString() + extension;
            
            // 保存文件
            Path filePath = IMAGE_DIR_PATH.resolve(filename);
            Files.write(filePath, file.getBytes());
            
            // 返回文件URL，使用 /api/uploads/images/ 确保通过后端Controller处理
            return "/api/uploads/images/" + filename;
        } catch (IOException e) {
            throw new BusinessException("上传失败: " + e.getMessage());
        }
    }
}

