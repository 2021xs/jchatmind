package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.ParsedCodeFile;
import com.kama.jchatmind.model.entity.CodeChunk;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

@Component
public class CodeChunkContextBuilder {

    public String build(ParsedCodeFile parsed, CodeChunk chunk, Map<String, Object> metadata) {
        String chunkType = safe(chunk.getChunkType());
        if ("CONTROLLER_API".equals(chunkType)) {
            return controllerApi(parsed, chunk, metadata);
        }
        if ("SERVICE_METHOD".equals(chunkType)) {
            return serviceMethod(parsed, metadata);
        }
        if ("MAPPER_METHOD".equals(chunkType)) {
            return mapperMethod(parsed, metadata);
        }
        if ("MYBATIS_SQL".equals(chunkType)) {
            return myBatisSql(metadata);
        }
        if ("CLASS_SUMMARY".equals(chunkType)) {
            return classSummary(parsed, metadata);
        }
        if ("CONFIG".equals(chunkType)) {
            return config(parsed);
        }
        if ("README".equals(chunkType)) {
            return readme();
        }
        if ("POM".equals(chunkType)) {
            return pom();
        }
        if ("SQL_FILE".equals(chunkType)) {
            return sqlFile();
        }
        if ("ENUM".equals(chunkType)) {
            return enumContext(parsed, metadata);
        }
        if ("JAVA_METHOD".equals(chunkType)) {
            return javaMethod(parsed, metadata);
        }
        return javaClass(parsed, metadata);
    }

    private String controllerApi(ParsedCodeFile parsed, CodeChunk chunk, Map<String, Object> metadata) {
        StringBuilder sb = base("This chunk is a Spring Controller API method.");
        append(sb, "It represents an HTTP entry point.");
        append(sb, "Class: " + first(metadata.get("className"), parsed.getClassName()) + ".");
        append(sb, "Method: " + first(metadata.get("methodName"), methodFromSymbol(chunk.getSymbolName())) + ".");
        String method = first(metadata.get("httpMethod"), chunk.getHttpMethod());
        String path = first(metadata.get("apiPath"), chunk.getApiPath());
        append(sb, "API: " + joinSpace(method, path) + ".");
        append(sb, "Use this chunk when the query asks about API, endpoint, request path, HTTP method, or controller entry.");
        return sb.toString();
    }

    private String serviceMethod(ParsedCodeFile parsed, Map<String, Object> metadata) {
        StringBuilder sb = base("This chunk is a service-layer method.");
        append(sb, "Class: " + first(metadata.get("className"), parsed.getClassName()) + ".");
        append(sb, "Method: " + first(metadata.get("methodName"), "") + ".");
        append(sb, "It usually contains business logic and may be called by controller APIs, scheduled tasks, or message consumers.");
        append(sb, "Use this chunk when the query asks about business logic, implementation, processing flow, validation, persistence, Redis, MQ, or cache operations.");
        return sb.toString();
    }

    private String mapperMethod(ParsedCodeFile parsed, Map<String, Object> metadata) {
        StringBuilder sb = base("This chunk is a Java Mapper interface method.");
        append(sb, "Class: " + first(metadata.get("className"), parsed.getClassName()) + ".");
        append(sb, "Method: " + first(metadata.get("methodName"), "") + ".");
        append(sb, "It represents a database access method and may correspond to a MyBatis XML SQL statement.");
        append(sb, "Use this chunk when the query asks about mapper method, DAO method, or database access entry.");
        return sb.toString();
    }

    private String myBatisSql(Map<String, Object> metadata) {
        StringBuilder sb = base("This chunk is a MyBatis SQL statement.");
        append(sb, "Mapper: " + first(metadata.get("mapperClass"), metadata.get("namespace")) + ".");
        append(sb, "SQL id: " + first(metadata.get("sqlId"), metadata.get("mapperMethod")) + ".");
        append(sb, "SQL type: " + first(metadata.get("sqlType"), "") + ".");
        append(sb, "Use this chunk when the query asks about SQL, table, select, insert, update, delete, or mapper XML.");
        return sb.toString();
    }

    private String classSummary(ParsedCodeFile parsed, Map<String, Object> metadata) {
        StringBuilder sb = base("This chunk is a class-level summary.");
        append(sb, "Class: " + first(metadata.get("className"), parsed.getClassName()) + ".");
        append(sb, "Java type: " + first(metadata.get("javaType"), parsed.getFileType()) + ".");
        append(sb, "It describes the responsibility, annotations, and method list of the class.");
        append(sb, "Use this chunk when the query asks about class responsibility, class purpose, or what a class does.");
        return sb.toString();
    }

    private String config(ParsedCodeFile parsed) {
        StringBuilder sb = base("This chunk is an application configuration file.");
        append(sb, "File: " + safe(parsed.getRelativePath()) + ".");
        append(sb, "It may define Redis, RabbitMQ, datasource, server, model, or application settings.");
        append(sb, "Use this chunk when the query asks about configuration, yaml, properties, settings, or application parameters.");
        return sb.toString();
    }

    private String readme() {
        StringBuilder sb = base("This chunk is project documentation.");
        append(sb, "It may describe project purpose, modules, architecture, usage, or deployment.");
        append(sb, "Use this chunk when the query asks about project overview, documentation, or general explanation.");
        return sb.toString();
    }

    private String pom() {
        StringBuilder sb = base("This chunk is Maven pom.xml.");
        append(sb, "It may define project dependencies, plugins, modules, Java version, or build configuration.");
        append(sb, "Use this chunk when the query asks about dependencies, Maven, build, plugins, or project version.");
        return sb.toString();
    }

    private String sqlFile() {
        StringBuilder sb = base("This chunk is a standalone SQL file.");
        append(sb, "It may define tables, indexes, schema initialization, or database seed data.");
        append(sb, "Use this chunk when the query asks about database schema, init SQL, table definition, or indexes.");
        return sb.toString();
    }

    private String enumContext(ParsedCodeFile parsed, Map<String, Object> metadata) {
        StringBuilder sb = base("This chunk is a Java enum.");
        append(sb, "Class: " + first(metadata.get("className"), parsed.getClassName()) + ".");
        append(sb, "It defines fixed constants or states.");
        append(sb, "Use this chunk when the query asks about enum values, status, state machine, or fixed options.");
        return sb.toString();
    }

    private String javaMethod(ParsedCodeFile parsed, Map<String, Object> metadata) {
        StringBuilder sb = base("This chunk is a Java method.");
        append(sb, "Class: " + first(metadata.get("className"), parsed.getClassName()) + ".");
        append(sb, "Method: " + first(metadata.get("methodName"), "") + ".");
        append(sb, "It contains implementation code.");
        append(sb, "Use this chunk when the query asks about a specific method or implementation detail.");
        return sb.toString();
    }

    private String javaClass(ParsedCodeFile parsed, Map<String, Object> metadata) {
        StringBuilder sb = base("This chunk is a Java class.");
        append(sb, "Class: " + first(metadata.get("className"), parsed.getClassName()) + ".");
        append(sb, "It may contain fields, methods, or general implementation logic.");
        append(sb, "Use this chunk when no more specific chunk type applies.");
        return sb.toString();
    }

    private StringBuilder base(String line) {
        StringBuilder sb = new StringBuilder();
        append(sb, line);
        return sb;
    }

    private void append(StringBuilder sb, String line) {
        String text = line == null ? "" : line.trim();
        if (!text.isBlank() && !text.endsWith(": .") && !text.endsWith(":.")) {
            sb.append(text).append('\n');
        }
    }

    private String first(Object value, Object fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (!text.isBlank()) {
            return text;
        }
        return fallback == null ? "" : String.valueOf(fallback).trim();
    }

    private String format(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) value;
            return String.join(", ", collection.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }

    private String joinSpace(String left, String right) {
        return (safe(left) + " " + safe(right)).trim();
    }

    private String methodFromSymbol(String symbolName) {
        if (symbolName == null || !symbolName.contains("#")) {
            return "";
        }
        return symbolName.substring(symbolName.lastIndexOf('#') + 1);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
