package com.gtcfesk.exchange.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final OperationLogInterceptor operationLogInterceptor;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 映射上传的图片文件
        // 使用与FileUploadController相同的路径逻辑
        Path uploadPath;
        try {
            // 尝试获取jar包所在目录
            String jarPath = WebConfig.class.getProtectionDomain()
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
            
            uploadPath = basePath.resolve("uploads/images").toAbsolutePath();
        } catch (Exception e) {
            // 如果上述方法失败，使用简单的相对路径
            uploadPath = Paths.get("uploads/images").toAbsolutePath();
        }
        
        String uploadPathStr = uploadPath.toString().replace("\\", "/");
        // 确保路径以 / 结尾
        if (!uploadPathStr.endsWith("/")) {
            uploadPathStr += "/";
        }
        
        System.out.println("[WebConfig] 静态资源映射: /uploads/images/** -> file:" + uploadPathStr);
        
        // 注释掉静态资源映射，使用ImageController来处理
        // 因为静态资源映射可能优先级过高，导致Controller无法被调用
        // registry.addResourceHandler("/uploads/images/**")
        //         .addResourceLocations("file:" + uploadPathStr)
        //         .setCachePeriod(3600); // 缓存1小时
        
        System.out.println("[WebConfig] 注意: 静态资源映射已禁用，使用ImageController处理图片请求");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(operationLogInterceptor)
                .addPathPatterns("/api/admin/**")
                .excludePathPatterns("/api/admin/operation-logs"); // 排除操作日志查询本身
    }
}

