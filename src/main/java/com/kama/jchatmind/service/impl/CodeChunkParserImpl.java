package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import com.kama.jchatmind.service.CodeChunkParser;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@AllArgsConstructor
public class CodeChunkParserImpl implements CodeChunkParser {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\b(class|interface)\\s+(\\w+)");
    private static final Pattern CLASS_REQUEST_MAPPING = Pattern.compile("@RequestMapping\\s*\\(([^)]*)\\)");
    private static final Pattern MAPPING_PATTERN = Pattern.compile("@(GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping)\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern METHOD_PATTERN = Pattern.compile("\\b(?:public|private|protected)\\s+[\\w<>\\[\\], ?]+\\s+(\\w+)\\s*\\(");
    private static final Pattern XML_NAMESPACE_PATTERN = Pattern.compile("<mapper\\s+namespace=\"([^\"]+)\"");
    private static final Pattern XML_STATEMENT_PATTERN = Pattern.compile(
            "<(select|insert|update|delete)\\s+[^>]*id=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</\\1>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TABLE_PATTERN = Pattern.compile("\\b(from|join|update|into)\\s+([`\\w.]+)", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    @Override
    public ParsedCodeFile parse(Path rootPath, Path filePath) {
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            String relativePath = rootPath.relativize(filePath).toString().replace("\\", "/");
            String fileType = resolveFileType(filePath);
            String packageName = extractFirst(PACKAGE_PATTERN, content);
            String className = extractClassName(content);
            List<CodeChunk> chunks = switch (fileType) {
                case "JAVA" -> parseJava(content);
                case "MYBATIS_XML" -> parseMyBatisXml(content);
                case "README" -> List.of(simpleChunk("README", "README", content, 1, lineCount(content), Map.of("fileType", fileType)));
                case "POM" -> List.of(simpleChunk("POM", "pom.xml", content, 1, lineCount(content), Map.of("fileType", fileType)));
                case "CONFIG" -> List.of(simpleChunk("CONFIG", filePath.getFileName().toString(), content, 1, lineCount(content), Map.of("fileType", fileType)));
                case "SQL_FILE" -> List.of(simpleChunk("SQL_FILE", filePath.getFileName().toString(), content, 1, lineCount(content), Map.of("tables", extractTables(content))));
                default -> List.of(simpleChunk("TEXT", filePath.getFileName().toString(), content, 1, lineCount(content), Map.of("fileType", fileType)));
            };
            return ParsedCodeFile.builder()
                    .relativePath(relativePath)
                    .fileType(fileType)
                    .packageName(packageName)
                    .className(className)
                    .checksum(sha256(content))
                    .chunks(chunks)
                    .build();
        } catch (IOException e) {
            throw new BizException("读取代码文件失败: " + filePath + ", " + e.getMessage());
        }
    }

    private List<CodeChunk> parseJava(String content) {
        String javaType = resolveJavaType(content);
        String className = extractClassName(content);
        List<CodeChunk> chunks = new ArrayList<>();
        chunks.add(simpleChunk(javaType, className, content, 1, lineCount(content), Map.of(
                "javaType", javaType,
                "className", className == null ? "" : className,
                "methods", extractMethodNames(content)
        )));
        if ("CONTROLLER".equals(javaType)) {
            chunks.addAll(parseControllerApis(content, className));
        }
        return chunks;
    }

    private List<CodeChunk> parseControllerApis(String content, String className) {
        String[] lines = content.split("\\R", -1);
        String classPath = extractMappingPath(extractFirst(CLASS_REQUEST_MAPPING, content));
        List<CodeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = MAPPING_PATTERN.matcher(lines[i]);
            if (!matcher.find()) {
                continue;
            }
            String annotation = matcher.group(1);
            String args = matcher.group(2);
            String methodName = findNextMethodName(lines, i);
            String methodPath = extractMappingPath(args);
            String apiPath = combinePath(classPath, methodPath);
            String httpMethod = resolveHttpMethod(annotation);
            int start = i + 1;
            int end = Math.min(lines.length, start + 40);
            String snippet = joinLines(lines, start, end);
            chunks.add(CodeChunk.builder()
                    .chunkType("API")
                    .symbolName((className == null ? "" : className) + "#" + (methodName == null ? "unknown" : methodName))
                    .apiPath(apiPath)
                    .httpMethod(httpMethod)
                    .startLine(start)
                    .endLine(end)
                    .content(snippet)
                    .metadata(toJson(Map.of("controller", className == null ? "" : className, "method", methodName == null ? "" : methodName)))
                    .createdAt(LocalDateTime.now())
                    .build());
        }
        return chunks;
    }

    private List<CodeChunk> parseMyBatisXml(String content) {
        String namespace = extractFirst(XML_NAMESPACE_PATTERN, content);
        List<CodeChunk> chunks = new ArrayList<>();
        Matcher matcher = XML_STATEMENT_PATTERN.matcher(content);
        while (matcher.find()) {
            String sqlType = matcher.group(1).toUpperCase();
            String id = matcher.group(2);
            String sql = cleanXml(matcher.group(3));
            chunks.add(CodeChunk.builder()
                    .chunkType("MYBATIS_SQL")
                    .symbolName((namespace == null ? "" : namespace) + "." + id)
                    .startLine(lineOf(content, matcher.start()))
                    .endLine(lineOf(content, matcher.end()))
                    .content(sql)
                    .metadata(toJson(Map.of(
                            "namespace", namespace == null ? "" : namespace,
                            "id", id,
                            "sqlType", sqlType,
                            "tables", extractTables(sql)
                    )))
                    .createdAt(LocalDateTime.now())
                    .build());
        }
        if (chunks.isEmpty()) {
            chunks.add(simpleChunk("MYBATIS_XML", namespace, content, 1, lineCount(content), Map.of("namespace", namespace == null ? "" : namespace)));
        }
        return chunks;
    }

    private CodeChunk simpleChunk(String chunkType, String symbolName, String content, int startLine, int endLine, Map<String, Object> metadata) {
        return CodeChunk.builder()
                .chunkType(chunkType)
                .symbolName(symbolName)
                .startLine(startLine)
                .endLine(endLine)
                .content(content)
                .metadata(toJson(metadata))
                .createdAt(LocalDateTime.now())
                .build();
    }

    private String resolveFileType(Path filePath) {
        String name = filePath.getFileName().toString();
        String lower = name.toLowerCase();
        if (lower.equals("readme.md")) return "README";
        if (lower.equals("pom.xml")) return "POM";
        if (lower.endsWith(".java")) return "JAVA";
        if (lower.endsWith("mapper.xml")) return "MYBATIS_XML";
        if (lower.endsWith(".sql")) return "SQL_FILE";
        if (lower.equals("application.yml") || lower.equals("application.yaml") || lower.equals("application.properties")) return "CONFIG";
        return "TEXT";
    }

    private String resolveJavaType(String content) {
        if (content.contains("@RestController") || content.contains("@Controller")) return "CONTROLLER";
        if (content.contains("@Service")) return "SERVICE";
        if (content.contains("@Mapper") || content.contains("extends BaseMapper")) return "MAPPER";
        return "JAVA_CLASS";
    }

    private String resolveHttpMethod(String annotation) {
        return switch (annotation) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            default -> "REQUEST";
        };
    }

    private String extractMappingPath(String args) {
        if (args == null || args.isBlank()) return "";
        Matcher matcher = Pattern.compile("\"([^\"]*)\"").matcher(args);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String combinePath(String base, String path) {
        String b = base == null ? "" : base.trim();
        String p = path == null ? "" : path.trim();
        if (b.isEmpty()) return p;
        if (p.isEmpty()) return b;
        return ("/" + b + "/" + p).replaceAll("/+", "/");
    }

    private String findNextMethodName(String[] lines, int startIndex) {
        for (int i = startIndex; i < Math.min(lines.length, startIndex + 8); i++) {
            Matcher matcher = METHOD_PATTERN.matcher(lines[i]);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    private List<String> extractMethodNames(String content) {
        List<String> methods = new ArrayList<>();
        Matcher matcher = METHOD_PATTERN.matcher(content);
        while (matcher.find() && methods.size() < 80) {
            methods.add(matcher.group(1));
        }
        return methods;
    }

    private List<String> extractTables(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            tables.add(matcher.group(2).replace("`", ""));
        }
        return new ArrayList<>(tables);
    }

    private String extractFirst(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractClassName(String content) {
        Matcher matcher = CLASS_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(2) : null;
    }

    private String cleanXml(String sql) {
        return sql.replaceAll("<!\\[CDATA\\[", "")
                .replaceAll("]]>", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private int lineCount(String content) {
        return content.isEmpty() ? 1 : content.split("\\R", -1).length;
    }

    private int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, content.length()); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    private String joinLines(String[] lines, int startLineOneBased, int endLineOneBased) {
        StringBuilder sb = new StringBuilder();
        for (int i = startLineOneBased - 1; i < endLineOneBased && i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BizException("计算文件 checksum 失败: " + e.getMessage());
        }
    }
}
