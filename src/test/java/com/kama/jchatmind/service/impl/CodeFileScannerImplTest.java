package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.service.CodeFileScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void skipsStandaloneSqlFilesByDefault() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/resources/db"));
        Path sql = tempDir.resolve("src/main/resources/db/hmdp.sql");
        Path mapper = tempDir.resolve("src/main/resources/OrderMapper.xml");
        Files.writeString(sql, "create table tb_user(id bigint);");
        Files.writeString(mapper, "<mapper namespace=\"demo.OrderMapper\"><select id=\"find\">select 1</select></mapper>");

        CodeRagProperties properties = new CodeRagProperties();
        properties.getAllowedRoots().add(tempDir.toString());
        CodeFileScannerImpl scanner = new CodeFileScannerImpl(properties);

        CodeFileScanner.ScanResult result = scanner.scan(tempDir);

        assertFalse(result.getFiles().contains(sql.toAbsolutePath().normalize()));
        assertTrue(result.getFiles().contains(mapper.toAbsolutePath().normalize()));
        assertEquals(1, result.getSkippedSqlFileCount());
        assertEquals("src/main/resources/db/hmdp.sql", result.getSkippedSqlFilePaths().get(0));
    }

    @Test
    void includesStandaloneSqlFilesWhenConfigured() throws Exception {
        Path sql = tempDir.resolve("schema.sql");
        Files.writeString(sql, "create table tb_user(id bigint);");

        CodeRagProperties properties = new CodeRagProperties();
        properties.setIncludeSqlFiles(true);
        properties.getAllowedRoots().add(tempDir.toString());
        CodeFileScannerImpl scanner = new CodeFileScannerImpl(properties);

        CodeFileScanner.ScanResult result = scanner.scan(tempDir);

        assertTrue(result.getFiles().contains(sql.toAbsolutePath().normalize()));
        assertEquals(0, result.getSkippedSqlFileCount());
    }
}
