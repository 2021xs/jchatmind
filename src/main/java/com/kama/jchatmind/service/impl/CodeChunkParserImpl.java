package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import com.kama.jchatmind.service.CodeChunkParser;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@AllArgsConstructor
@Slf4j
public class CodeChunkParserImpl implements CodeChunkParser {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\b(class|interface|enum)\\s+(\\w+)");
    private static final Pattern METHOD_PATTERN = Pattern.compile("\\b(?:public|private|protected)\\s+[\\w<>\\[\\], ?]+\\s+(\\w+)\\s*\\(");
    private static final Pattern XML_DOCTYPE_PATTERN = Pattern.compile("(?is)<!DOCTYPE\\s+mapper\\s+[^>]*(?:\\[[\\s\\S]*?]\\s*)?>");
    private static final Pattern TABLE_PATTERN = Pattern.compile("\\b(from|join|update|into)\\s+([`\\w.]+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> XML_STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");
    private static final Set<String> MYBATIS_DYNAMIC_TAGS = Set.of(
            "if", "choose", "when", "otherwise", "foreach", "where", "set", "trim", "bind"
    );

    private final ObjectMapper objectMapper;

    @Override
    public ParsedCodeFile parse(Path rootPath, Path filePath) {
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            String relativePath = rootPath.relativize(filePath).toString().replace("\\", "/");
            String fileType = resolveFileType(filePath);
            String packageName = extractFirst(PACKAGE_PATTERN, content);
            String className = extractClassName(content);
            List<CodeChunk> chunks;
            switch (fileType) {
                case "JAVA":
                    chunks = parseJava(content, packageName);
                    break;
                case "MYBATIS_XML":
                    chunks = parseMyBatisXml(content, filePath.getFileName().toString());
                    break;
                case "README":
                    chunks = List.of(simpleChunk("README", "README", content, 1, lineCount(content), Map.of("fileType", fileType)));
                    break;
                case "POM":
                    chunks = List.of(simpleChunk("POM", "pom.xml", content, 1, lineCount(content), Map.of("fileType", fileType)));
                    break;
                case "CONFIG":
                    chunks = List.of(simpleChunk("CONFIG", filePath.getFileName().toString(), content, 1, lineCount(content), Map.of("fileName", filePath.getFileName().toString(), "fileType", fileType)));
                    break;
                case "SQL_FILE":
                    chunks = List.of(simpleChunk("SQL_FILE", filePath.getFileName().toString(), content, 1, lineCount(content), Map.of("tables", extractTables(content))));
                    break;
                default:
                    chunks = List.of(simpleChunk("TEXT", filePath.getFileName().toString(), content, 1, lineCount(content), Map.of("fileType", fileType)));
                    break;
            }
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

    private List<CodeChunk> parseJava(String content, String packageName) {
        try {
            CompilationUnit compilationUnit = StaticJavaParser.parse(content);
            List<CodeChunk> chunks = new ArrayList<>();
            for (TypeDeclaration<?> type : compilationUnit.getTypes()) {
                chunks.addAll(parseJavaType(content, packageName, type));
            }
            if (!chunks.isEmpty()) {
                return chunks;
            }
        } catch (RuntimeException ignored) {
            // Fall back to the previous lightweight regex parser for incomplete Java files.
        }
        return parseJavaFallback(content);
    }

    private List<CodeChunk> parseJavaType(String content, String packageName, TypeDeclaration<?> type) {
        String className = type.getNameAsString();
        String qualifiedClassName = isBlank(packageName) ? className : packageName + "." + className;
        String javaType = resolveJavaType(type);
        List<String> annotations = annotationNames(type.getAnnotations());
        List<String> methodNames = type.getMethods().stream().map(MethodDeclaration::getNameAsString).toList();
        List<SymbolMetadata> constants = extractConstantSymbols(type, className);
        int startLine = beginLine(type).orElse(1);
        int endLine = endLine(type).orElse(lineCount(content));
        List<CodeChunk> chunks = new ArrayList<>();

        if (type instanceof EnumDeclaration) {
            EnumDeclaration enumDeclaration = (EnumDeclaration) type;
            chunks.add(simpleChunk("ENUM", qualifiedClassName, enumDeclaration.toString(), startLine, endLine, Map.of(
                    "packageName", empty(packageName),
                    "className", className,
                    "javaType", "ENUM",
                    "annotations", annotations,
                    "enumConstants", enumDeclaration.getEntries().stream().map(entry -> entry.getNameAsString()).toList()
            )));
            return chunks;
        }

        Map<String, Object> classMetadata = new LinkedHashMap<>();
        classMetadata.put("packageName", empty(packageName));
        classMetadata.put("className", className);
        classMetadata.put("qualifiedClassName", qualifiedClassName);
        classMetadata.put("javaType", javaType);
        classMetadata.put("annotations", annotations);
        classMetadata.put("methods", methodNames);
        mergeSymbolMetadata(classMetadata, constants);
        chunks.add(simpleChunk("CLASS_SUMMARY", qualifiedClassName,
                buildClassSummary(packageName, className, javaType, annotations, methodNames),
                startLine, endLine, classMetadata));

        String classPath = findMappingPath(type.getAnnotations());
        for (MethodDeclaration method : type.getMethods()) {
            Optional<AnnotationExpr> mapping = findMappingAnnotation(method.getAnnotations());
            String chunkType = resolveMethodChunkType(javaType, method, mapping.isPresent());
            if (chunkType == null) {
                continue;
            }
            chunks.add(buildMethodChunk(content, packageName, className, qualifiedClassName, javaType, classPath, method, mapping.orElse(null), chunkType));
        }
        return chunks;
    }

    private CodeChunk buildMethodChunk(String content, String packageName, String className, String qualifiedClassName,
                                      String javaType, String classPath, MethodDeclaration method,
                                      AnnotationExpr mappingAnnotation, String chunkType) {
        int start = beginLine(method).orElse(1);
        int end = endLine(method).orElse(start);
        String methodName = method.getNameAsString();
        String signature = method.getDeclarationAsString(false, false, false);
        String apiPath = null;
        String httpMethod = null;
        if ("CONTROLLER_API".equals(chunkType)) {
            apiPath = combinePath(classPath, mappingAnnotation == null ? "" : extractMappingPath(mappingAnnotation.toString()));
            httpMethod = mappingAnnotation == null ? null : resolveHttpMethod(mappingAnnotation.getNameAsString());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("packageName", empty(packageName));
        metadata.put("className", className);
        metadata.put("qualifiedClassName", qualifiedClassName);
        metadata.put("methodName", methodName);
        metadata.put("method", methodName);
        metadata.put("signature", signature);
        metadata.put("returnType", method.getTypeAsString());
        metadata.put("parameters", method.getParameters().stream().map(Object::toString).toList());
        metadata.put("annotations", annotationNames(method.getAnnotations()));
        metadata.put("javaType", javaType);
        metadata.put("apiPath", empty(apiPath));
        metadata.put("httpMethod", empty(httpMethod));
        List<SymbolMetadata> symbols = new ArrayList<>();
        symbols.add(new SymbolMetadata(methodName, "", "METHOD",
                normalizedValues(methodName, className, qualifiedClassName)));
        if (!isBlank(apiPath)) {
            symbols.add(new SymbolMetadata(apiPath, apiPath, "API_PATH",
                    normalizedValues(apiPath, methodName, className)));
        }
        if ("MAPPER".equals(javaType)) {
            metadata.put("relatedSqlId", methodName);
            metadata.put("relatedSymbol", className + "#" + methodName);
            symbols.add(new SymbolMetadata(methodName, methodName, "SQL_ID",
                    normalizedValues(methodName, className)));
        }
        mergeSymbolMetadata(metadata, symbols);
        metadata.put("startLine", start);
        metadata.put("endLine", end);
        return CodeChunk.builder()
                .chunkType(chunkType)
                .symbolName(qualifiedClassName + "#" + methodName)
                .apiPath(apiPath)
                .httpMethod(httpMethod)
                .startLine(start)
                .endLine(end)
                .content(extractLines(content, start, end))
                .metadata(toJson(metadata))
                .createdAt(LocalDateTime.now())
                .build();
    }

    private List<CodeChunk> parseJavaFallback(String content) {
        String javaType = resolveJavaType(content);
        String className = extractClassName(content);
        return List.of(simpleChunk("CLASS_SUMMARY", className, content, 1, lineCount(content), Map.of(
                "javaType", javaType,
                "className", className == null ? "" : className,
                "methods", extractMethodNames(content),
                "parserFallback", true
        )));
    }

    private List<CodeChunk> parseMyBatisXml(String content, String fileName) {
        List<CodeChunk> chunks = new ArrayList<>();
        Document document;
        try {
            document = parseXmlDocument(content);
        } catch (Exception e) {
            log.warn("Failed to parse MyBatis mapper XML with secure XML parser: fileName={}, error={}", fileName, e.getMessage());
            chunks.add(simpleChunk("MYBATIS_XML", fileName, content, 1, lineCount(content), Map.of(
                    "fileName", fileName,
                    "parserFallback", true,
                    "parserError", e.getMessage() == null ? "" : e.getMessage()
            )));
            return chunks;
        }

        Element mapper = document.getDocumentElement();
        if (mapper == null || !"mapper".equals(mapper.getTagName())) {
            log.warn("MyBatis mapper XML root is not <mapper>: fileName={}", fileName);
            chunks.add(simpleChunk("MYBATIS_XML", fileName, content, 1, lineCount(content), Map.of(
                    "fileName", fileName,
                    "parserFallback", true,
                    "parserError", "root element is not mapper"
            )));
            return chunks;
        }

        String namespace = mapper.getAttribute("namespace");
        if (isBlank(namespace)) {
            log.warn("MyBatis mapper XML namespace is missing: fileName={}", fileName);
        }
        Map<String, Element> sqlFragments = collectSqlFragments(mapper);
        NodeList children = mapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element statement) || !XML_STATEMENT_TAGS.contains(statement.getTagName())) {
                continue;
            }
            String sqlId = statement.getAttribute("id");
            if (isBlank(sqlId)) {
                log.warn("Skip MyBatis statement without id: fileName={}, statementType={}", fileName, statement.getTagName());
                continue;
            }
            chunks.add(buildMyBatisStatementChunk(content, fileName, namespace, statement, sqlFragments, sqlId));
        }
        if (chunks.isEmpty()) {
            chunks.add(simpleChunk("MYBATIS_XML", isBlank(namespace) ? fileName : namespace, content, 1, lineCount(content), Map.of(
                    "namespace", empty(namespace),
                    "fileName", fileName
            )));
        }
        return chunks;
    }

    private CodeChunk buildMyBatisStatementChunk(String fileContent,
                                                 String fileName,
                                                 String namespace,
                                                 Element statement,
                                                 Map<String, Element> sqlFragments,
                                                 String sqlId) {
        String statementType = statement.getTagName().toUpperCase();
        String mapperClass = simpleClassName(namespace);
        String fullSqlId = isBlank(namespace) ? fileName + "." + sqlId : namespace + "." + sqlId;
        String relatedSymbol = (isBlank(mapperClass) ? fileName : mapperClass) + "#" + sqlId;
        Element expandedStatement = (Element) statement.cloneNode(true);
        LinkedHashSet<String> includeRefs = new LinkedHashSet<>();
        List<String> includeWarnings = new ArrayList<>();
        expandIncludes(expandedStatement, sqlFragments, namespace, new LinkedHashSet<>(), includeRefs, includeWarnings);
        String statementContent = wrapMapper(namespace, serializeNode(expandedStatement));
        LinkedHashSet<String> dynamicTags = new LinkedHashSet<>();
        collectElementTags(expandedStatement, dynamicTags, MYBATIS_DYNAMIC_TAGS);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("namespace", empty(namespace));
        metadata.put("fileName", fileName);
        metadata.put("mapperClass", mapperClass);
        metadata.put("mapperMethod", sqlId);
        metadata.put("sqlId", sqlId);
        metadata.put("id", sqlId);
        metadata.put("fullSqlId", fullSqlId);
        metadata.put("statementType", statementType);
        metadata.put("sqlType", statementType);
        metadata.put("dynamicSql", !dynamicTags.isEmpty());
        metadata.put("dynamicTags", new ArrayList<>(dynamicTags));
        metadata.put("includeRefs", new ArrayList<>(includeRefs));
        metadata.put("includeExpanded", includeWarnings.isEmpty());
        metadata.put("includeWarnings", includeWarnings);
        metadata.put("relatedSymbol", relatedSymbol);
        List<SymbolMetadata> symbols = new ArrayList<>();
        symbols.add(new SymbolMetadata(sqlId, sqlId, "SQL_ID", normalizedValues(sqlId, mapperClass, namespace, fullSqlId)));
        mergeSymbolMetadata(metadata, symbols);

        int startLine = findStatementStartLine(fileContent, sqlId, statement.getTagName());
        int endLine = findStatementEndLine(fileContent, sqlId, statement.getTagName(), startLine);
        return CodeChunk.builder()
                .chunkType("MYBATIS_SQL")
                .symbolName(fullSqlId)
                .startLine(startLine)
                .endLine(endLine)
                .content(statementContent)
                .metadata(toJson(metadata))
                .createdAt(LocalDateTime.now())
                .build();
    }

    private Document parseXmlDocument(String content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        secureXmlFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        secureXmlFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        secureXmlFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        secureXmlFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException ignored) {
            // Some JAXP implementations do not support these attributes.
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new java.io.StringReader("")));
        String sanitized = XML_DOCTYPE_PATTERN.matcher(content).replaceFirst("");
        return builder.parse(new ByteArrayInputStream(sanitized.getBytes(StandardCharsets.UTF_8)));
    }

    private void secureXmlFeature(DocumentBuilderFactory factory, String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (Exception e) {
            log.warn("XML parser does not support security feature: feature={}, enabled={}, error={}",
                    feature, enabled, e.getMessage());
        }
    }

    private Map<String, Element> collectSqlFragments(Element mapper) {
        Map<String, Element> fragments = new LinkedHashMap<>();
        NodeList children = mapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && "sql".equals(element.getTagName())) {
                String id = element.getAttribute("id");
                if (!isBlank(id)) {
                    fragments.put(id, element);
                }
            }
        }
        return fragments;
    }

    private void expandIncludes(Element element,
                                Map<String, Element> sqlFragments,
                                String namespace,
                                LinkedHashSet<String> includeStack,
                                LinkedHashSet<String> includeRefs,
                                List<String> includeWarnings) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element childElement)) {
                continue;
            }
            if ("include".equals(childElement.getTagName())) {
                String refid = childElement.getAttribute("refid");
                includeRefs.add(refid);
                String localRefid = localIncludeRefid(refid, namespace);
                Element fragment = isBlank(localRefid) ? null : sqlFragments.get(localRefid);
                if (isBlank(refid)) {
                    warnInclude(includeWarnings, "missing refid");
                    continue;
                }
                if (fragment == null) {
                    warnInclude(includeWarnings, "unresolved include refid=" + refid);
                    continue;
                }
                if (!includeStack.add(localRefid)) {
                    warnInclude(includeWarnings, "circular include refid=" + refid);
                    continue;
                }
                Element expandedFragment = (Element) fragment.cloneNode(true);
                expandIncludes(expandedFragment, sqlFragments, namespace, includeStack, includeRefs, includeWarnings);
                Node parent = childElement.getParentNode();
                NodeList fragmentChildren = expandedFragment.getChildNodes();
                List<Node> clones = new ArrayList<>();
                for (int j = 0; j < fragmentChildren.getLength(); j++) {
                    clones.add(fragmentChildren.item(j).cloneNode(true));
                }
                for (Node clone : clones) {
                    parent.insertBefore(parent.getOwnerDocument().importNode(clone, true), childElement);
                }
                parent.removeChild(childElement);
                includeStack.remove(localRefid);
            } else {
                expandIncludes(childElement, sqlFragments, namespace, includeStack, includeRefs, includeWarnings);
            }
        }
    }

    private String localIncludeRefid(String refid, String namespace) {
        if (isBlank(refid)) {
            return "";
        }
        if (refid.contains(".")) {
            String prefix = empty(namespace) + ".";
            return refid.startsWith(prefix) ? refid.substring(prefix.length()) : "";
        }
        return refid;
    }

    private void warnInclude(List<String> warnings, String warning) {
        warnings.add(warning);
        log.warn("MyBatis include expansion skipped: {}", warning);
    }

    private void collectElementTags(Element element, Set<String> collector, Set<String> targetTags) {
        if (targetTags.contains(element.getTagName())) {
            collector.add(element.getTagName());
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                collectElementTags(childElement, collector, targetTags);
            }
        }
    }

    private String wrapMapper(String namespace, String statementContent) {
        String namespaceAttribute = isBlank(namespace) ? "" : " namespace=\"" + escapeXmlAttribute(namespace) + "\"";
        return "<mapper" + namespaceAttribute + ">\n" + statementContent.trim() + "\n</mapper>";
    }

    private String serializeNode(Node node) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            try {
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            } catch (IllegalArgumentException ignored) {
                // Some TransformerFactory implementations do not support these attributes.
            }
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(node), new StreamResult(writer));
            return writer.toString().trim();
        } catch (Exception e) {
            log.warn("Failed to serialize MyBatis XML node: error={}", e.getMessage());
            return "";
        }
    }

    private String escapeXmlAttribute(String value) {
        return empty(value)
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private int findStatementStartLine(String content, String sqlId, String tagName) {
        Matcher matcher = Pattern.compile("(?is)<" + Pattern.quote(tagName) + "\\b[^>]*>").matcher(content);
        while (matcher.find()) {
            if (xmlAttributeEquals(matcher.group(), "id", sqlId)) {
                return lineOf(content, matcher.start());
            }
        }
        return 1;
    }

    private int findStatementEndLine(String content, String sqlId, String tagName, int startLine) {
        Matcher matcher = Pattern.compile("(?is)<" + Pattern.quote(tagName) + "\\b[^>]*>").matcher(content);
        while (matcher.find()) {
            if (!xmlAttributeEquals(matcher.group(), "id", sqlId)) {
                continue;
            }
            Matcher endMatcher = Pattern.compile("(?is)</\\s*" + Pattern.quote(tagName) + "\\s*>").matcher(content);
            if (endMatcher.find(matcher.end())) {
                return lineOf(content, endMatcher.end());
            }
            return startLine;
        }
        return startLine;
    }

    private boolean xmlAttributeEquals(String tagText, String attributeName, String expectedValue) {
        Matcher matcher = Pattern.compile("(?is)\\b" + Pattern.quote(attributeName)
                + "\\s*=\\s*(['\"])(.*?)\\1").matcher(tagText);
        return matcher.find() && expectedValue.equals(matcher.group(2));
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

    private String resolveJavaType(TypeDeclaration<?> type) {
        if (type instanceof EnumDeclaration) {
            return "ENUM";
        }
        List<String> annotations = annotationNames(type.getAnnotations());
        if (annotations.contains("RestController") || annotations.contains("Controller")) {
            return "CONTROLLER";
        }
        if (annotations.contains("Service")) {
            return "SERVICE";
        }
        if (annotations.contains("Mapper")) {
            return "MAPPER";
        }
        if (type instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration declaration = (ClassOrInterfaceDeclaration) type;
            if (declaration.isInterface() && declaration.getExtendedTypes().stream()
                    .anyMatch(typeName -> typeName.getNameAsString().equals("BaseMapper"))) {
                return "MAPPER";
            }
            return declaration.isInterface() ? "INTERFACE" : "JAVA_CLASS";
        }
        return "JAVA_CLASS";
    }

    private String resolveMethodChunkType(String javaType, MethodDeclaration method, boolean hasMappingAnnotation) {
        if ("CONTROLLER".equals(javaType) && hasMappingAnnotation) {
            return "CONTROLLER_API";
        }
        if ("SERVICE".equals(javaType)) {
            return "SERVICE_METHOD";
        }
        if ("MAPPER".equals(javaType)) {
            return "MAPPER_METHOD";
        }
        if (method.isPublic() || method.isProtected()) {
            return "JAVA_METHOD";
        }
        return null;
    }

    private List<String> annotationNames(List<AnnotationExpr> annotations) {
        return annotations.stream().map(annotation -> annotation.getName().getIdentifier()).toList();
    }

    private Optional<AnnotationExpr> findMappingAnnotation(List<AnnotationExpr> annotations) {
        return annotations.stream()
                .filter(annotation -> isMappingAnnotation(annotation.getNameAsString()))
                .findFirst();
    }

    private boolean isMappingAnnotation(String annotationName) {
        return "GetMapping".equals(annotationName)
                || "PostMapping".equals(annotationName)
                || "PutMapping".equals(annotationName)
                || "DeleteMapping".equals(annotationName)
                || "RequestMapping".equals(annotationName);
    }

    private List<SymbolMetadata> extractConstantSymbols(TypeDeclaration<?> type, String className) {
        List<SymbolMetadata> symbols = new ArrayList<>();
        for (FieldDeclaration field : type.getFields()) {
            if (!field.isStatic() || !field.isFinal() || !"String".equals(field.getElementType().asString())) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                String name = variable.getNameAsString();
                String literalValue = variable.getInitializer()
                        .map(this::literalValue)
                        .orElse("");
                String symbolType = inferSymbolType(name, literalValue, className);
                symbols.add(new SymbolMetadata(name, literalValue, symbolType,
                        normalizedValues(name, literalValue, className)));
            }
        }
        return symbols;
    }

    private String literalValue(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return expression.asStringLiteralExpr().asString();
        }
        return expression.toString();
    }

    private String inferSymbolType(String name, String value, String className) {
        String text = (empty(name) + " " + empty(value) + " " + empty(className)).toLowerCase();
        String upperName = empty(name).toUpperCase();
        if (upperName.endsWith("_ROUTING_KEY") || text.contains("routing")) {
            return "MQ_ROUTING_KEY";
        }
        if (upperName.endsWith("_EXCHANGE") || text.contains("exchange")) {
            return "MQ_EXCHANGE";
        }
        if (upperName.endsWith("_QUEUE") || text.contains("queue")) {
            return "MQ_QUEUE";
        }
        if (upperName.endsWith("_KEY") && (text.contains("redis") || text.contains("cache"))) {
            return "REDIS_KEY";
        }
        if (upperName.endsWith("_KEY")) {
            return "CONFIG_KEY";
        }
        return "CONSTANT";
    }

    private void mergeSymbolMetadata(Map<String, Object> metadata, List<SymbolMetadata> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return;
        }
        LinkedHashSet<String> symbolNames = new LinkedHashSet<>(stringList(metadata.get("symbols")));
        LinkedHashSet<String> literalValues = new LinkedHashSet<>(stringList(metadata.get("literalValues")));
        LinkedHashSet<String> symbolTypes = new LinkedHashSet<>(stringList(metadata.get("symbolTypes")));
        LinkedHashSet<String> normalizedSymbols = new LinkedHashSet<>(stringList(metadata.get("normalizedSymbols")));
        for (SymbolMetadata symbol : symbols) {
            addIfPresent(symbolNames, symbol.symbolName());
            addIfPresent(literalValues, symbol.literalValue());
            addIfPresent(symbolTypes, symbol.symbolType());
            for (String normalized : symbol.normalizedTexts()) {
                addIfPresent(normalizedSymbols, normalized);
            }
        }
        if (!symbolNames.isEmpty()) {
            metadata.put("symbols", new ArrayList<>(symbolNames));
        }
        if (!literalValues.isEmpty()) {
            metadata.put("literalValues", new ArrayList<>(literalValues));
        }
        if (!symbolTypes.isEmpty()) {
            metadata.put("symbolTypes", new ArrayList<>(symbolTypes));
        }
        if (!normalizedSymbols.isEmpty()) {
            metadata.put("normalizedSymbols", new ArrayList<>(normalizedSymbols));
        }
    }

    private List<String> normalizedValues(String... values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            addIfPresent(normalized, normalizeSymbolText(value));
        }
        return new ArrayList<>(normalized);
    }

    private String normalizeSymbolText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String splitCamel = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        String cleaned = splitCamel
                .replace('_', ' ')
                .replace('.', ' ')
                .replace(':', ' ')
                .replace('-', ' ')
                .replace('/', ' ');
        return cleaned.toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?>) {
            return ((List<?>) value).stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private String findMappingPath(List<AnnotationExpr> annotations) {
        return annotations.stream()
                .filter(annotation -> "RequestMapping".equals(annotation.getNameAsString()))
                .findFirst()
                .map(annotation -> extractMappingPath(annotation.toString()))
                .orElse("");
    }

    private String buildClassSummary(String packageName, String className, String javaType, List<String> annotations, List<String> methodNames) {
        return "package: " + empty(packageName) + "\n"
                + "class: " + className + "\n"
                + "javaType: " + javaType + "\n"
                + "annotations: " + String.join(", ", annotations) + "\n"
                + "methods: " + String.join(", ", methodNames);
    }

    private String resolveJavaType(String content) {
        if (content.contains("@RestController") || content.contains("@Controller")) return "CONTROLLER";
        if (content.contains("@Service")) return "SERVICE";
        if (content.contains("@Mapper") || content.contains("extends BaseMapper")) return "MAPPER";
        return "JAVA_CLASS";
    }

    private String resolveHttpMethod(String annotation) {
        switch (annotation) {
            case "GetMapping":
                return "GET";
            case "PostMapping":
                return "POST";
            case "PutMapping":
                return "PUT";
            case "DeleteMapping":
                return "DELETE";
            default:
                return "REQUEST";
        }
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

    private String extractLines(String content, int startLineOneBased, int endLineOneBased) {
        String[] lines = content.split("\\R", -1);
        int start = Math.max(1, startLineOneBased);
        int end = Math.max(start, Math.min(endLineOneBased, lines.length));
        return joinLines(lines, start, end);
    }

    private Optional<Integer> beginLine(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(position -> position.line);
    }

    private Optional<Integer> endLine(com.github.javaparser.ast.Node node) {
        return node.getEnd().map(position -> position.line);
    }

    private String simpleClassName(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return "";
        }
        int index = namespace.lastIndexOf('.');
        return index >= 0 ? namespace.substring(index + 1) : namespace;
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    private record SymbolMetadata(String symbolName,
                                  String literalValue,
                                  String symbolType,
                                  List<String> normalizedTexts) {
    }
}
