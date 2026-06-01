package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.service.CodeFileScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeFileScannerImplTest {
    @TempDir
    Path tempDir;

    @Test
    void includesLuaFilesInCodeRagImportScan() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/resources/lua"));
        Path lua = tempDir.resolve("src/main/resources/lua/seckill.lua");
        Files.writeString(lua, "return redis.call('get', KEYS[1])");
        Files.writeString(tempDir.resolve("notes.txt"), "ignored");

        CodeRagProperties properties = new CodeRagProperties();
        properties.getAllowedRoots().add(tempDir.toString());
        CodeFileScannerImpl scanner = new CodeFileScannerImpl(properties);

        CodeFileScanner.ScanResult result = scanner.scan(tempDir);

        assertTrue(result.getFiles().contains(lua.toAbsolutePath().normalize()));
    }
}
