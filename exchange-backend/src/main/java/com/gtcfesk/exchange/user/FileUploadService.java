package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.Base64MultipartFile;
import com.gtcfesk.exchange.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;

@Service
public class FileUploadService {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.simulation.SimulationEnvironment simulation;
    
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.common.UploadStorage storage;

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
            throw new BusinessException("签名图片内容无效或上传失败");
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
            Path filePath = com.gtcfesk.exchange.common.ImageFiles.save(file, com.gtcfesk.exchange.tenant.TenantFiles.directory(storage.images()));
            String filename = com.gtcfesk.exchange.tenant.TenantFiles.ownerPath()+"/"+filePath.getFileName().toString();

            // 返回文件URL，使用 /api/uploads/images/ 确保通过后端Controller处理
            return (simulation != null && simulation.enabled() ? "/demo-uploads/images/" : "/api/uploads/images/") + filename;
        } catch (IOException e) {
            throw new BusinessException("图片保存失败，请稍后重试");
        }
    }
}

