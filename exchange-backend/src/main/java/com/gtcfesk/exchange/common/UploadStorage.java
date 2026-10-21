package com.gtcfesk.exchange.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.AbstractResource;
import org.springframework.web.multipart.MultipartFile;
import com.gtcfesk.exchange.tenant.TenantFiles;
import java.io.*;
import java.util.*;

/** Shared storage for multipart uploads, signatures and authorized media delivery. */
@Component
public final class UploadStorage {
    private final Path root;
    @Autowired(required=false) private MinioObjectStorage objects;
    @Value("${file.storage:local}") private String mode = "local";
    public UploadStorage(@Value("${file.upload-dir:uploads}") String directory) {
        if(directory==null || directory.trim().isEmpty()) throw new IllegalArgumentException("上传目录配置不能为空");
        root=Paths.get(directory).toAbsolutePath().normalize();
        if(Files.exists(root) && !Files.isDirectory(root)) throw new IllegalArgumentException("上传目录必须是目录");
    }
    public Path images(){return root.resolve("images");}
    public Path audio(){return root.resolve("audio");}
    /** Bind an administrator's validated upload to the customer so normal private-media rules still apply. */
    public String bindCustomerImage(String url, Long userId) {
        if (url == null || url.isEmpty()) return null;
        String prefix = "/api/uploads/images/";
        if (!url.startsWith(prefix)) throw new BusinessException("请上传本站图片");
        String filename = url.substring(prefix.length());
        String customer = com.gtcfesk.exchange.tenant.TenantContext.requireTenantId() + "/user/" + userId + "/";
        String staff = TenantFiles.ownerPath() + "/";
        if (!filename.matches("[0-9]+/(?:user|staff)/(?:agent-)?-?[0-9]{1,19}/[a-zA-Z0-9_.-]+")
                || (!filename.startsWith(customer) && !filename.startsWith(staff)))
            throw new org.springframework.security.access.AccessDeniedException("图片不属于当前客户或操作员");
        if (filename.lastIndexOf('.') < filename.lastIndexOf('/')) throw new BusinessException("图片格式不支持");
        String extension = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        if (!Arrays.asList(".png", ".jpg", ".jpeg", ".gif", ".webp").contains(extension)) throw new BusinessException("图片格式不支持");
        if (filename.startsWith(customer)) return url;
        String target = customer + UUID.randomUUID() + extension;
        try {
            if (minioMode()) {
                Resource source = read("images", filename);
                if (source.contentLength() > 5L * 1024 * 1024) throw new BusinessException("图片大小不能超过5MB");
                try (InputStream stream = source.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int length;
                    while ((length = stream.read(buffer)) != -1) bytes.write(buffer, 0, length);
                    save("images", target, bytes.toByteArray(), ".gif".equals(extension) ? "image/gif" : ".png".equals(extension) ? "image/png" : "image/jpeg");
                }
            } else {
                Path base = images().toAbsolutePath().normalize();
                Path source = base.resolve(filename).normalize().toRealPath();
                if (!source.startsWith(base.toRealPath().resolve(staff).normalize()) || Files.size(source) > 5L * 1024 * 1024)
                    throw new BusinessException("图片无效");
                Path directory = base.resolve(customer).normalize(); Files.createDirectories(directory);
                if (!directory.toRealPath().startsWith(base.toRealPath())) throw new BusinessException("图片目录无效");
                Files.copy(source, base.resolve(target));
            }
            return prefix + target;
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException("图片保存失败，请重新上传"); }
    }
    public boolean minioMode() {
        if (!"local".equals(mode) && !"minio".equals(mode)) throw new BusinessException("文件存储配置无效");
        return "minio".equals(mode);
    }
    public String saveImage(MultipartFile file) throws IOException {
        if (!minioMode()) {
            Path path = ImageFiles.save(file, TenantFiles.directory(images()));
            return TenantFiles.ownerPath() + "/" + path.getFileName();
        }
        Map.Entry<String, byte[]> image = ImageFiles.encode(file);
        String filename = TenantFiles.ownerPath() + "/" + UUID.randomUUID() + image.getKey();
        save("images", filename, image.getValue(), ".gif".equals(image.getKey()) ? "image/gif" : "image/png");
        return filename;
    }
    public void save(String kind, String filename, byte[] bytes, String contentType) throws IOException {
        if (!minioMode()) {
            TenantFiles.directory(root.resolve(kind));
            Files.write(root.resolve(kind).resolve(filename), bytes);
            return;
        }
        try (InputStream stream = new ByteArrayInputStream(bytes)) {
            objects.put(kind + "/" + filename, stream, bytes.length, contentType);
        } catch (Exception e) { throw new IOException("素材上传至 MinIO 失败", e); }
    }
    public Resource readThumbnail(String filename) throws Exception {
        try { return read("thumbnails", filename); }
        catch (io.minio.errors.ErrorResponseException error) {
            if (!"NoSuchKey".equals(error.errorResponse().code())) throw error;
        }
        String original = "images/" + filename;
        long size = objects.stat(original).size();
        byte[] thumbnail;
        try (InputStream stream = objects.read(original, 0, size)) { thumbnail = ImageFiles.thumbnail(stream); }
        save("thumbnails", filename, thumbnail, "image/png");
        return read("thumbnails", filename);
    }
    public Resource read(String kind, String filename) throws Exception {
        String object = kind + "/" + filename;
        long size = objects.stat(object).size();
        return new AbstractResource() {
            @Override public String getDescription() { return "private media"; }
            @Override public String getFilename() { return filename.substring(filename.lastIndexOf('/') + 1); }
            @Override public long contentLength() { return size; }
            @Override public InputStream getInputStream() throws IOException {
                try { return objects.read(object, 0, size); }
                catch (Exception e) { throw new IOException("素材存储暂时不可用", e); }
            }
        };
    }
}
