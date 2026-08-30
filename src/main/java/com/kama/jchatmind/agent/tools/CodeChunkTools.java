package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.CodeChunkExactReadResult;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.tool.ToolArgumentException;
import com.kama.jchatmind.tool.ToolPolicyRejectedException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Component
public class CodeChunkTools implements Tool {
    public static final String TRUSTED_REPO_ID_TOOL_CONTEXT_KEY =
            CodeChunkTools.class.getName() + ".trustedRepoId";

    private static final String STATUS_READY = "READY";

    private final CodeChunkMapper codeChunkMapper;
    private final CodeRepositoryMapper codeRepositoryMapper;

    public CodeChunkTools(CodeChunkMapper codeChunkMapper, CodeRepositoryMapper codeRepositoryMapper) {
        this.codeChunkMapper = codeChunkMapper;
        this.codeRepositoryMapper = codeRepositoryMapper;
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "getCodeChunk",
            description = "Exactly reread one known code chunk using repoId and chunkId returned by searchProjectCode. This is a scoped ID lookup, not semantic search, and returns the full canonical code_chunk.content."
    )
    public String getCodeChunk(
            @org.springframework.ai.tool.annotation.ToolParam(
                    description = "Repository UUID from the selected searchProjectCode evidence") String repoId,
            @org.springframework.ai.tool.annotation.ToolParam(
                    description = "Chunk UUID from the selected searchProjectCode evidence") String chunkId,
            ToolContext toolContext) {
        String requestedRepoId = normalizeUuid(repoId, "repoId");
        String requestedChunkId = normalizeUuid(chunkId, "chunkId");
        String trustedRepoId = trustedRepoId(toolContext);
        if (!requestedRepoId.equals(trustedRepoId)) {
            throw new ToolPolicyRejectedException(
                    "CODE_CHUNK_SCOPE_MISMATCH: requested repoId is outside the trusted runtime repository scope");
        }

        CodeRepository repository = codeRepositoryMapper.selectById(trustedRepoId);
        if (repository == null) {
            throw new ToolPolicyRejectedException("CODE_REPOSITORY_NOT_FOUND: trusted repository does not exist");
        }
        if (!STATUS_READY.equals(repository.getStatus())) {
            throw new ToolPolicyRejectedException(
                    "CODE_REPOSITORY_NOT_READY: repository status=" + repository.getStatus());
        }

        CodeChunkExactReadResult chunk = codeChunkMapper.selectByRepoIdAndChunkId(
                trustedRepoId, requestedChunkId);
        if (chunk == null) {
            throw new ToolArgumentException(
                    "CODE_CHUNK_NOT_FOUND: no exact chunk matches the supplied repoId and chunkId", null);
        }
        return format(chunk);
    }

    private String trustedRepoId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new ToolPolicyRejectedException(
                    "CODE_CHUNK_SCOPE_UNAVAILABLE: no trusted runtime repository scope");
        }
        Object value = toolContext.getContext().get(TRUSTED_REPO_ID_TOOL_CONTEXT_KEY);
        if (!(value instanceof String repoId) || !StringUtils.hasText(repoId)) {
            throw new ToolPolicyRejectedException(
                    "CODE_CHUNK_SCOPE_UNAVAILABLE: no trusted runtime repository scope");
        }
        try {
            return UUID.fromString(repoId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ToolPolicyRejectedException(
                    "CODE_CHUNK_SCOPE_INVALID: trusted runtime repository scope is invalid");
        }
    }

    private String normalizeUuid(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new ToolArgumentException(fieldName + " is required for exact code chunk reread", null);
        }
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ToolArgumentException(fieldName + " must be a valid UUID", e);
        }
    }

    private String format(CodeChunkExactReadResult chunk) {
        StringBuilder out = new StringBuilder("Exact code chunk:\n");
        append(out, "repoId", chunk.getRepoId());
        append(out, "chunkId", chunk.getChunkId());
        append(out, "file", chunk.getFilePath());
        append(out, "symbol", chunk.getSymbolName());
        append(out, "type", chunk.getChunkType());
        append(out, "lines", lineRange(chunk));
        out.append("\ncontent:\n");
        if (chunk.getContent() != null) {
            out.append(chunk.getContent());
        }
        out.append('\n');
        return out.toString();
    }

    private void append(StringBuilder out, String name, String value) {
        out.append(name).append(": ").append(value == null ? "" : value).append('\n');
    }

    private String lineRange(CodeChunkExactReadResult chunk) {
        if (chunk.getStartLine() == null) {
            return null;
        }
        return chunk.getEndLine() == null
                ? chunk.getStartLine().toString()
                : chunk.getStartLine() + "-" + chunk.getEndLine();
    }
}
