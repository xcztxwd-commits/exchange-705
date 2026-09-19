package com.gtcfesk.exchange.common;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.io.IOException;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.util.*;
/** Shared by multipart uploads and base64 contract signatures. */
public final class ImageFiles {
    private ImageFiles() { }
    public static Path save(MultipartFile file, Path directory) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw new BusinessException("图片为空或超过5MB");
        BufferedImage decoded;
        String format;
        try (java.io.InputStream stream = file.getInputStream(); ImageInputStream input = ImageIO.createImageInputStream(stream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException("文件内容不是有效图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Arrays.asList("png", "jpeg", "jpg", "gif", "bmp").contains(format)) throw new BusinessException("图片格式不支持");
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 25000000L) throw new BusinessException("图片尺寸过大");
                decoded = reader.read(0);
            } finally { reader.dispose(); }
        }
        if (decoded == null) throw new BusinessException("图片内容无效");
        // Retain GIF animation; never use the client filename or extension.
        Path path = directory.resolve(UUID.randomUUID().toString() + ("gif".equals(format) ? ".gif" : ".png"));
        if ("gif".equals(format)) Files.write(path, file.getBytes());
        else if (!ImageIO.write(decoded, "png", path.toFile())) throw new IOException("Image encoding failed");
        return path;
    }
}
