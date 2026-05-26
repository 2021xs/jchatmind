package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeChunkEmbeddingTextBuilderImplTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void controllerApiContextContainsApiMetadata() throws Exception {
        CodeChunkEmbeddingTextBuilderImpl builder = builder(true);
        String text = builder.build(parsed("src/main/java/demo/VoucherOrderController.java", "JAVA", "VoucherOrderController"),
                chunk("CONTROLLER_API", "demo.VoucherOrderController#querySeckillResult", """
                        public Result querySeckillResult(Long orderId) {
                            return Result.ok();
                        }
                        """, Map.of(
                        "className", "VoucherOrderController",
                        "methodName", "querySeckillResult",
                        "httpMethod", "GET",
                        "apiPath", "/voucher-order/seckill/result/{orderId}"
                )));

        assertTrue(text.contains("context:\nThis chunk is a Spring Controller API method."));
        assertTrue(text.contains("It represents an HTTP entry point."));
        assertTrue(text.contains("API: GET /voucher-order/seckill/result/{orderId}."));
        assertTrue(text.indexOf("context:") < text.indexOf("content:"));
    }

    @Test
    void serviceMethodContextContainsBusinessLogicHint() throws Exception {
        CodeChunkEmbeddingTextBuilderImpl builder = builder(true);
        String text = builder.build(parsed("src/main/java/demo/VoucherOrderServiceImpl.java", "JAVA", "VoucherOrderServiceImpl"),
                chunk("SERVICE_METHOD", "demo.VoucherOrderServiceImpl#createVoucherOrder", "void createVoucherOrder() {}",
                        Map.of("className", "VoucherOrderServiceImpl", "methodName", "createVoucherOrder")));

        assertTrue(text.contains("This chunk is a service-layer method."));
        assertTrue(text.contains("Method: createVoucherOrder."));
        assertTrue(text.contains("business logic"));
    }

    @Test
    void myBatisSqlContextContainsMapperSqlAndStatementContentWithoutTablesMetadata() throws Exception {
        CodeChunkEmbeddingTextBuilderImpl builder = builder(true);
        String text = builder.build(parsed("src/main/resources/mapper/VoucherOrderMapper.xml", "MYBATIS_XML", ""),
                chunk("MYBATIS_SQL", "VoucherOrderMapper#selectExistingUserVoucherPairs",
                        "<select id=\"selectExistingUserVoucherPairs\">select * from tb_voucher_order</select>",
                        Map.of(
                                "mapperClass", "VoucherOrderMapper",
                                "sqlId", "selectExistingUserVoucherPairs",
                                "sqlType", "SELECT"
                        )));

        assertTrue(text.contains("This chunk is a MyBatis SQL statement."));
        assertTrue(text.contains("Mapper: VoucherOrderMapper."));
        assertTrue(text.contains("SQL id: selectExistingUserVoucherPairs."));
        assertTrue(text.contains("select * from tb_voucher_order"));
        assertFalse(text.contains("tables:"));
        assertFalse(text.contains("Tables:"));
    }

    @Test
    void configContextContainsConfigurationHints() throws Exception {
        CodeChunkEmbeddingTextBuilderImpl builder = builder(true);
        String text = builder.build(parsed("src/main/resources/application.yaml", "CONFIG", ""),
                chunk("CONFIG", "application.yaml", "spring:\n  datasource:\n    url: jdbc:postgresql://localhost/db", Map.of()));

        assertTrue(text.contains("This chunk is an application configuration file."));
        assertTrue(text.contains("configuration, yaml, properties, settings"));
    }

    @Test
    void disabledContextualPrefixDoesNotEmitContext() throws Exception {
        CodeChunkEmbeddingTextBuilderImpl builder = builder(false);
        String text = builder.build(parsed("src/main/java/demo/UserService.java", "JAVA", "UserService"),
                chunk("SERVICE_METHOD", "demo.UserService#login", "void login() {}", Map.of("methodName", "login")));

        assertFalse(text.startsWith("context:"));
        assertFalse(text.contains("This chunk is a service-layer method."));
        assertTrue(text.contains("content:\nvoid login() {}"));
    }

    private CodeChunkEmbeddingTextBuilderImpl builder(boolean contextualPrefixEnabled) {
        CodeRagProperties properties = new CodeRagProperties();
        properties.getEmbeddingMetadata().setEnabled(true);
        properties.getContextualPrefix().setEnabled(contextualPrefixEnabled);
        return new CodeChunkEmbeddingTextBuilderImpl(objectMapper, properties, new CodeChunkContextBuilder());
    }

    private ParsedCodeFile parsed(String relativePath, String fileType, String className) {
        return ParsedCodeFile.builder()
                .relativePath(relativePath)
                .fileType(fileType)
                .packageName("com.demo")
                .className(className)
                .build();
    }

    private CodeChunk chunk(String chunkType, String symbolName, String content, Map<String, Object> metadata) throws Exception {
        return CodeChunk.builder()
                .chunkType(chunkType)
                .symbolName(symbolName)
                .apiPath((String) metadata.get("apiPath"))
                .httpMethod((String) metadata.get("httpMethod"))
                .content(content)
                .metadata(objectMapper.writeValueAsString(metadata))
                .build();
    }
}
