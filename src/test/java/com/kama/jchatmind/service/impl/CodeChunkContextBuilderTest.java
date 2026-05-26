package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeChunkContextBuilderTest {
    private final CodeChunkContextBuilder builder = new CodeChunkContextBuilder();

    @Test
    void controllerApiContextContainsApiMethodAndPath() {
        String context = builder.build(parsed("src/main/java/demo/UserController.java", "JAVA", "UserController"),
                chunk("CONTROLLER_API", "demo.UserController#login", "POST", "/user/login"),
                Map.of("className", "UserController", "methodName", "login"));

        assertTrue(context.contains("Spring Controller API method"));
        assertTrue(context.contains("API: POST /user/login."));
        assertTrue(context.contains("Method: login."));
    }

    @Test
    void serviceMethodContextContainsServiceLayerAndMethodName() {
        String context = builder.build(parsed("src/main/java/demo/UserService.java", "JAVA", "UserService"),
                chunk("SERVICE_METHOD", "demo.UserService#login", null, null),
                Map.of("className", "UserService", "methodName", "login"));

        assertTrue(context.contains("service-layer method"));
        assertTrue(context.contains("Method: login."));
    }

    @Test
    void myBatisSqlContextContainsMapperSqlTypeWithoutTablesMetadata() {
        String context = builder.build(parsed("src/main/resources/mapper/UserMapper.xml", "MYBATIS_XML", ""),
                chunk("MYBATIS_SQL", "UserMapper#selectById", null, null),
                Map.of("mapperClass", "UserMapper", "sqlId", "selectById", "sqlType", "SELECT"));

        assertTrue(context.contains("MyBatis SQL statement"));
        assertTrue(context.contains("Mapper: UserMapper."));
        assertTrue(context.contains("SQL id: selectById."));
        assertTrue(context.contains("SQL type: SELECT."));
        assertFalse(context.contains("Tables:"));
    }

    @Test
    void configContextContainsConfigurationAndSettings() {
        String context = builder.build(parsed("src/main/resources/application.yaml", "CONFIG", ""),
                chunk("CONFIG", "application.yaml", null, null),
                Map.of());

        assertTrue(context.contains("application configuration file"));
        assertTrue(context.contains("settings"));
    }

    @Test
    void missingFieldsDoNotThrow() {
        assertDoesNotThrow(() -> builder.build(parsed(null, null, null),
                chunk("CONTROLLER_API", null, null, null),
                Map.of()));
    }

    private ParsedCodeFile parsed(String relativePath, String fileType, String className) {
        return ParsedCodeFile.builder()
                .relativePath(relativePath)
                .fileType(fileType)
                .className(className)
                .build();
    }

    private CodeChunk chunk(String chunkType, String symbolName, String httpMethod, String apiPath) {
        return CodeChunk.builder()
                .chunkType(chunkType)
                .symbolName(symbolName)
                .httpMethod(httpMethod)
                .apiPath(apiPath)
                .build();
    }
}
