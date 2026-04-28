package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.service.CodeFileScanner;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
@AllArgsConstructor
public class CodeFileScannerImpl implements CodeFileScanner {
    private static final Set<String> IGNORED_DIRS = Set.of(".git", ".idea", "target", "node_modules", "logs");

    private final CodeRagProperties properties;

    @Override
    public ScanResult scan(Path rootPath) {
        Path normalizedRoot = rootPath.toAbsolutePath().normalize();
        validateRoot(normalizedRoot);

        List<Path> result = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> paths = Files.walk(normalizedRoot)) {
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (result.size() >= properties.getMaxFilesPerImport()) {
                    truncated = true;
                    break;
                }
                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.startsWith(normalizedRoot)) {
                    continue;
                }
                if (Files.isDirectory(normalized) || !Files.isRegularFile(normalized)) {
                    continue;
                }
                if (isUnderIgnoredDirectory(normalizedRoot, normalized)) {
                    continue;
                }
                if (!isSupportedFile(normalized)) {
                    continue;
                }
                if (Files.size(normalized) > properties.getMaxFileSizeBytes()) {
                    continue;
                }
                result.add(normalized);
            }
        } catch (IOException e) {
            throw new BizException("扫描代码库失败: " + e.getMessage());
        }

        String message = truncated
                ? "达到 max-files-per-import 限制，已停止扫描，结果可能不完整"
                : "代码库扫描完成";
        return new ScanResult(normalizedRoot, result, truncated, message);
    }

    private void validateRoot(Path normalizedRoot) {
        if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            throw new BizException("代码库根目录不存在或不是目录: " + normalizedRoot);
        }
        if (properties.getAllowedRoots() == null || properties.getAllowedRoots().isEmpty()) {
            throw new BizException("未配置 jchatmind.code-rag.allowed-roots，拒绝扫描");
        }
        boolean allowed = properties.getAllowedRoots().stream()
                .map(root -> Paths.get(root).toAbsolutePath().normalize())
                .anyMatch(normalizedRoot::startsWith);
        if (!allowed) {
            throw new BizException("rootPath 不在允许扫描目录内: " + normalizedRoot);
        }
    }

    private boolean isUnderIgnoredDirectory(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path part : relative) {
            String name = part.toString();
            if (name.startsWith(".") || IGNORED_DIRS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSupportedFile(Path file) {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase();
        return lower.equals("readme.md")
                || lower.equals("pom.xml")
                || lower.endsWith(".java")
                || lower.endsWith("mapper.xml")
                || lower.endsWith(".sql")
                || lower.equals("application.yml")
                || lower.equals("application.yaml")
                || lower.equals("application.properties");
    }
}
