package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeChunkParserImplTest {
    private final CodeChunkParserImpl parser = new CodeChunkParserImpl(new ObjectMapper());

    @TempDir
    Path tempDir;

    @Test
    void parsesControllerApiWithRealMethodBoundary() throws Exception {
        Path file = write("UserController.java", """
                package com.demo;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/users")
                public class UserController {
                    @GetMapping("/{id}")
                    public String getUser(Long id) {
                        return "ok";
                    }
                }
                """);

        ParsedCodeFile parsed = parser.parse(tempDir, file);

        CodeChunk apiChunk = findChunk(parsed, "CONTROLLER_API");
        assertEquals("com.demo.UserController#getUser", apiChunk.getSymbolName());
        assertEquals("/users/{id}", apiChunk.getApiPath());
        assertEquals("GET", apiChunk.getHttpMethod());
        assertTrue(apiChunk.getContent().contains("public String getUser"));
        assertTrue(apiChunk.getMetadata().contains("\"methodName\":\"getUser\""));
    }

    @Test
    void parsesServiceMethodChunk() throws Exception {
        Path file = write("OrderService.java", """
                package com.demo;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    public void createOrder(Long userId) {
                        validate(userId);
                    }
                    private void validate(Long userId) {
                    }
                }
                """);

        ParsedCodeFile parsed = parser.parse(tempDir, file);

        CodeChunk serviceChunk = findChunk(parsed, "SERVICE_METHOD");
        assertEquals("com.demo.OrderService#createOrder", serviceChunk.getSymbolName());
        assertTrue(serviceChunk.getMetadata().contains("\"signature\""));
        assertTrue(serviceChunk.getMetadata().contains("createOrder"));
    }

    @Test
    void parsesMyBatisSqlWeakLinkMetadata() throws Exception {
        Path file = write("OrderMapper.xml", """
                <mapper namespace="com.demo.OrderMapper">
                    <update id="decrementStock">
                        update tb_order set stock = stock - 1 where id = #{id}
                    </update>
                </mapper>
                """);

        ParsedCodeFile parsed = parser.parse(tempDir, file);

        CodeChunk sqlChunk = findChunk(parsed, "MYBATIS_SQL");
        assertEquals("com.demo.OrderMapper.decrementStock", sqlChunk.getSymbolName());
        assertTrue(sqlChunk.getMetadata().contains("\"mapperClass\":\"OrderMapper\""));
        assertTrue(sqlChunk.getMetadata().contains("\"sqlId\":\"decrementStock\""));
        assertTrue(sqlChunk.getMetadata().contains("\"relatedSymbol\":\"OrderMapper#decrementStock\""));
    }

    @Test
    void parsesMyBatisXmlStatementsWithNamespaceDynamicTagsAndCdata() throws Exception {
        Path file = write("OrderMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper
                        PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.demo.OrderMapper">
                    <!-- stable comment -->
                    <select resultType="Order"
                            id="selectPaid"
                            parameterType="map">
                        select * from tb_order
                        <where>
                            <if test="userId != null">
                                user_id = #{userId}
                            </if>
                            <![CDATA[
                            and status <> 'CANCELLED'
                            ]]>
                        </where>
                    </select>
                </mapper>
                """);

        ParsedCodeFile parsed = parser.parse(tempDir, file);

        CodeChunk chunk = findChunkBySqlId(parsed, "selectPaid");
        Map<?, ?> metadata = metadata(chunk);
        assertEquals("com.demo.OrderMapper", metadata.get("namespace"));
        assertEquals("selectPaid", metadata.get("sqlId"));
        assertEquals("com.demo.OrderMapper.selectPaid", metadata.get("fullSqlId"));
        assertEquals("SELECT", metadata.get("statementType"));
        assertEquals(true, metadata.get("dynamicSql"));
        assertFalse(metadata.containsKey("tables"));
        assertTrue(metadata.get("dynamicTags").toString().contains("where"));
        assertTrue(metadata.get("dynamicTags").toString().contains("if"));
        assertTrue(chunk.getContent().contains("<mapper namespace=\"com.demo.OrderMapper\">"));
        assertTrue(chunk.getContent().contains("<select"));
        assertTrue(chunk.getContent().contains("tb_order"));
        assertTrue(chunk.getContent().contains("#{userId}"));
        assertTrue(chunk.getContent().contains("status"));
    }

    @Test
    void parsesInsertUpdateAndDeleteStatements() throws Exception {
        Path file = write("OrderMapper.xml", """
                <mapper namespace="com.demo.OrderMapper">
                    <insert id="insertOrder">insert into tb_order(id) values(#{id})</insert>
                    <update id="updateOrder">update tb_order set status = #{status}</update>
                    <delete id="deleteOrder">delete from tb_order where id = #{id}</delete>
                </mapper>
                """);

        ParsedCodeFile parsed = parser.parse(tempDir, file);

        assertEquals(3, parsed.getChunks().stream().filter(chunk -> "MYBATIS_SQL".equals(chunk.getChunkType())).count());
        assertEquals("INSERT", metadata(findChunkBySqlId(parsed, "insertOrder")).get("statementType"));
        assertEquals("UPDATE", metadata(findChunkBySqlId(parsed, "updateOrder")).get("statementType"));
        assertEquals("DELETE", metadata(findChunkBySqlId(parsed, "deleteOrder")).get("statementType"));
    }

    @Test
    void expandsNestedIncludesAndRecordsIncludeRefs() throws Exception {
        Path file = write("OrderMapper.xml", """
                <mapper namespace="com.demo.OrderMapper">
                    <sql id="Base_Columns">id, user_id</sql>
                    <sql id="Audit_Columns"><include refid="Base_Columns"/>, created_at</sql>
                    <select id="selectOrder">
                        select <include refid="Audit_Columns"/> from tb_order
                    </select>
                </mapper>
                """);

        ParsedCodeFile parsed = parser.parse(tempDir, file);

        CodeChunk chunk = findChunkBySqlId(parsed, "selectOrder");
        Map<?, ?> metadata = metadata(chunk);
        assertTrue(chunk.getContent().contains("id, user_id"));
        assertTrue(chunk.getContent().contains("created_at"));
        assertTrue(metadata.get("includeRefs").toString().contains("Audit_Columns"));
        assertTrue(metadata.get("includeRefs").toString().contains("Base_Columns"));
        assertEquals(true, metadata.get("includeExpanded"));
    }

    @Test
    void includeFailuresKeepStatementAndDoNotAbortParsing() throws Exception {
        Path file = write("OrderMapper.xml", """
                <mapper namespace="com.demo.OrderMapper">
                    <sql id="A"><include refid="B"/></sql>
                    <sql id="B"><include refid="A"/></sql>
                    <select id="missingInclude">
                        select <include refid="Missing_Columns"/> from tb_order
                    </select>
                    <select id="circularInclude">
                        select <include refid="A"/> from tb_order
                    </select>
                </mapper>
                """);

        ParsedCodeFile parsed = parser.parse(tempDir, file);

        CodeChunk missing = findChunkBySqlId(parsed, "missingInclude");
        CodeChunk circular = findChunkBySqlId(parsed, "circularInclude");
        assertTrue(missing.getContent().contains("Missing_Columns"));
        assertTrue(metadata(missing).get("includeWarnings").toString().contains("unresolved include"));
        assertTrue(metadata(circular).get("includeWarnings").toString().contains("circular include"));
        assertEquals(2, parsed.getChunks().stream().filter(chunk -> "MYBATIS_SQL".equals(chunk.getChunkType())).count());
    }

    @Test
    void missingNamespaceStillParsesStatementWithFileNameFullSqlId() throws Exception {
        Path file = write("OrderMapper.xml", """
                <mapper>
                    <select id="selectWithoutNamespace">
                        select * from tb_order
                    </select>
                </mapper>
                """);

        CodeChunk chunk = findChunkBySqlId(parser.parse(tempDir, file), "selectWithoutNamespace");
        Map<?, ?> metadata = metadata(chunk);

        assertEquals("", metadata.get("namespace"));
        assertEquals("OrderMapper.xml.selectWithoutNamespace", metadata.get("fullSqlId"));
        assertEquals("OrderMapper.xml.selectWithoutNamespace", chunk.getSymbolName());
    }


    @Test
    void detectsForeachChooseWhenOtherwiseTrimSetAndBindDynamicTags() throws Exception {
        Path file = write("OrderMapper.xml", """
                <mapper namespace="com.demo.OrderMapper">
                    <update id="batchUpdate">
                        <bind name="now" value="_parameter.now"/>
                        update tb_order
                        <set>
                            <trim suffixOverrides=",">
                                status = #{status},
                            </trim>
                        </set>
                        where id in
                        <foreach collection="ids" item="id" open="(" separator="," close=")">
                            #{id}
                        </foreach>
                        <choose>
                            <when test="paid">and status = 'PAID'</when>
                            <otherwise>and status is not null</otherwise>
                        </choose>
                    </update>
                </mapper>
                """);

        Map<?, ?> metadata = metadata(findChunkBySqlId(parser.parse(tempDir, file), "batchUpdate"));

        String dynamicTags = metadata.get("dynamicTags").toString();
        assertEquals(true, metadata.get("dynamicSql"));
        assertTrue(dynamicTags.contains("foreach"));
        assertTrue(dynamicTags.contains("choose"));
        assertTrue(dynamicTags.contains("when"));
        assertTrue(dynamicTags.contains("otherwise"));
        assertTrue(dynamicTags.contains("trim"));
        assertTrue(dynamicTags.contains("set"));
        assertTrue(dynamicTags.contains("bind"));
    }

    @Test
    void invalidMyBatisXmlFallsBackToWholeFileChunk() throws Exception {
        Path file = write("BrokenMapper.xml", """
                <mapper namespace="com.demo.BrokenMapper">
                    <select id="broken">
                </mapper>
                """);

        ParsedCodeFile parsed = parser.parse(tempDir, file);

        assertEquals(1, parsed.getChunks().size());
        CodeChunk chunk = parsed.getChunks().get(0);
        assertEquals("MYBATIS_XML", chunk.getChunkType());
        assertTrue(metadata(chunk).get("parserFallback").toString().contains("true"));
        assertFalse(chunk.getContent().isBlank());
    }

    @Test
    void parsesJavaStringConstantsIntoSymbolMetadata() throws Exception {
        Path file = write("RabbitConstants.java", """
                package com.demo;
                public class RabbitConstants {
                    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";
                    private static final String CACHE_SHOP_KEY = "cache:shop:";
                }
                """);

        ParsedCodeFile parsed = parser.parse(tempDir, file);

        CodeChunk classChunk = findChunk(parsed, "CLASS_SUMMARY");
        Map<?, ?> metadata = new ObjectMapper().readValue(classChunk.getMetadata(), Map.class);
        assertTrue(metadata.get("symbols").toString().contains("SECKILL_ORDER_QUEUE"));
        assertTrue(metadata.get("symbols").toString().contains("CACHE_SHOP_KEY"));
        assertTrue(metadata.get("literalValues").toString().contains("seckill.order.queue"));
        assertTrue(metadata.get("symbolTypes").toString().contains("MQ_QUEUE"));
        assertTrue(metadata.get("symbolTypes").toString().contains("REDIS_KEY"));
        assertTrue(metadata.get("normalizedSymbols").toString().contains("seckill order queue"));
        assertTrue(metadata.get("normalizedSymbols").toString().contains("cache shop"));
    }

    private Path write(String fileName, String content) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }

    private CodeChunk findChunk(ParsedCodeFile parsed, String chunkType) {
        return parsed.getChunks().stream()
                .filter(chunk -> chunkType.equals(chunk.getChunkType()))
                .findFirst()
                .orElseThrow();
    }

    private CodeChunk findChunkBySqlId(ParsedCodeFile parsed, String sqlId) {
        return parsed.getChunks().stream()
                .filter(chunk -> "MYBATIS_SQL".equals(chunk.getChunkType()))
                .filter(chunk -> chunk.getMetadata().contains("\"sqlId\":\"" + sqlId + "\""))
                .findFirst()
                .orElseThrow();
    }

    private Map<?, ?> metadata(CodeChunk chunk) throws Exception {
        return new ObjectMapper().readValue(chunk.getMetadata(), Map.class);
    }
}
