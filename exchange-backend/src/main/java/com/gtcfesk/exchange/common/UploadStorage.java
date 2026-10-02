package com.gtcfesk.exchange.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One configured filesystem root shared by multipart, signature uploads and authorized delivery. */
@Component
public final class UploadStorage {
    private final Path root;
    public UploadStorage(@Value("${file.upload-dir:uploads}") String directory) {
        if(directory==null || directory.trim().isEmpty()) throw new IllegalArgumentException("上传目录配置不能为空");
        root=Paths.get(directory).toAbsolutePath().normalize();
        if(Files.exists(root) && !Files.isDirectory(root)) throw new IllegalArgumentException("上传目录必须是目录");
    }
    public Path images(){return root.resolve("images");}
    public Path audio(){return root.resolve("audio");}
}
