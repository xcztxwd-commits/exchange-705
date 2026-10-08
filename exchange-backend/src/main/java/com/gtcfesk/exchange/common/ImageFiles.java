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
    public static byte[] thumbnail(java.io.InputStream stream) throws IOException {
        try (ImageInputStream input = new javax.imageio.stream.MemoryCacheImageInputStream(stream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException("文件内容不是有效图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 25000000L) throw new BusinessException("图片尺寸过大");
                int sample = Math.max(1, (Math.max(width, height) + 319) / 320);
                ImageReadParam params = reader.getDefaultReadParam();
                params.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage image = reader.read(0, params);
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                try { if (image == null || !ImageIO.write(image, "png", bytes)) throw new IOException("Thumbnail encoding failed"); }
                finally { if (image != null) image.flush(); }
                return bytes.toByteArray();
            } finally { reader.dispose(); }
        }
    }
    public static Map.Entry<String, byte[]> encode(MultipartFile file) throws IOException {
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
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        if ("gif".equals(format)) return new AbstractMap.SimpleImmutableEntry<>(".gif", file.getBytes());
        if (!ImageIO.write(decoded, "png", bytes)) throw new IOException("Image encoding failed");
        return new AbstractMap.SimpleImmutableEntry<>(".png", bytes.toByteArray());
    }
    public static Path save(MultipartFile file, Path directory) throws IOException {
        Map.Entry<String, byte[]> image = encode(file);
        Path path = directory.resolve(UUID.randomUUID().toString() + image.getKey());
        Files.write(path, image.getValue());
        return path;
    }
}
