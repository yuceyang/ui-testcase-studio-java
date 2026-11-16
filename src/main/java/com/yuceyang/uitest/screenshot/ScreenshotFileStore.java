package com.yuceyang.uitest.screenshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ScreenshotFileStore {
    private final Path root;

    public ScreenshotFileStore(@Value("${qts.storage.directory}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize().resolve("screenshots");
    }

    public StoredScreenshot store(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(digest.digest(bytes));
            Path target = root.resolve(hash.substring(0, 2)).resolve(hash + ".png").normalize();
            if (!target.startsWith(root)) throw new IOException("截图路径越界");
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                Path temporary = target.resolveSibling(UUID.randomUUID() + ".tmp");
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, target);
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    Files.deleteIfExists(temporary);
                }
            }
            return new StoredScreenshot(hash, root.relativize(target).toString().replace('\\', '/'));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new ScreenshotException("截图存储失败", e);
        }
    }

    /** 截图根目录（导出 Excel 嵌图时需要绝对路径）。 */
    public Path root() {
        return root;
    }

    /** 相对路径换成绝对路径，越界或不存在时返回 null。 */
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) return null;
        return target;
    }

    public InputStream open(String relativePath) throws IOException {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) throw new IOException("截图路径越界");
        return Files.newInputStream(target);
    }

    public record StoredScreenshot(String sha256, String relativePath) {}
}
