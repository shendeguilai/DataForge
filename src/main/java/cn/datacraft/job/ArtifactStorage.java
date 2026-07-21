package cn.datacraft.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Component
public class ArtifactStorage {
    private final Path root;

    public ArtifactStorage(@Value("${dataforge.runtime-dir:./runtime}") String runtimeDir) throws IOException {
        this.root = Paths.get(runtimeDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public Path root() {
        return root;
    }

    public Path finalZip(UUID jobId) {
        return root.resolve(keyFor(jobId));
    }

    public Path pendingZip(UUID jobId) {
        return root.resolve(keyFor(jobId) + ".partial");
    }

    public Path publish(UUID jobId, Path pending) throws IOException {
        Path expected = pendingZip(jobId);
        Path normalizedPending = pending.toAbsolutePath().normalize();
        if (!normalizedPending.equals(expected)) {
            throw new IllegalArgumentException("临时数据包不在受控目录中");
        }
        Path target = finalZip(jobId);
        try {
            return Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            return Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public String toStoredKey(Path artifact) {
        if (artifact == null) return null;
        Path normalized = artifact.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.getParent() == null || !normalized.getParent().equals(root)) {
            throw new IllegalArgumentException("数据包路径不在运行目录中");
        }
        return normalized.getFileName().toString();
    }

    public Path resolveStored(String stored) {
        if (stored == null || stored.trim().isEmpty()) return null;
        try {
            Path candidate = Paths.get(stored.trim());
            if (candidate.isAbsolute()) {
                Path normalized = candidate.normalize();
                if (!normalized.startsWith(root) || normalized.getParent() == null || !normalized.getParent().equals(root)) {
                    throw new IllegalStateException("历史数据包路径不在当前运行目录中，请先执行路径迁移");
                }
                return normalized;
            }
            if (candidate.getNameCount() != 1 || ".".equals(candidate.toString()) || "..".equals(candidate.toString())) {
                throw new IllegalStateException("数据库中的数据包路径不合法");
            }
            Path resolved = root.resolve(candidate).normalize();
            if (!resolved.startsWith(root) || !resolved.getParent().equals(root)) {
                throw new IllegalStateException("数据库中的数据包路径越过运行目录");
            }
            return resolved;
        } catch (InvalidPathException exception) {
            throw new IllegalStateException("数据库中的数据包路径无法解析", exception);
        }
    }

    public static String keyFor(UUID jobId) {
        return "dataforge-" + jobId + ".zip";
    }
}
