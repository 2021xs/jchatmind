package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.mapper.CodeChunkMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.CodeChunkExactReadResult;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import com.kama.jchatmind.service.CodeSearchService;
import com.kama.jchatmind.service.EmbeddingService;
import com.kama.jchatmind.service.impl.CodeLlmEvidenceSelector;
import com.kama.jchatmind.tool.ToolArgumentException;
import com.kama.jchatmind.tool.ToolPolicyRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodeChunkToolsTest {
    private static final String R1 = "11111111-1111-1111-1111-111111111111";
    private static final String R2 = "22222222-2222-2222-2222-222222222222";
    private static final String C1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String C2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private CodeChunkMapper chunkMapper;
    private CodeRepositoryMapper repositoryMapper;
    private CodeChunkTools tools;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(CodeChunkMapper.class);
        repositoryMapper = mock(CodeRepositoryMapper.class);
        tools = new CodeChunkTools(chunkMapper, repositoryMapper);
    }

    @Test
    void exactHitReturnsCanonicalContentAndMetadataWithoutSearchComponents() {
        when(repositoryMapper.selectById(R1)).thenReturn(repository(R1, "READY"));
        when(chunkMapper.selectByRepoIdAndChunkId(R1, C1)).thenReturn(chunk(C1, "MARKER_ABC"));

        String result = tools.getCodeChunk(R1, C1, scoped(R1));

        assertTrue(result.contains("repoId: " + R1));
        assertTrue(result.contains("chunkId: " + C1));
        assertTrue(result.contains("file: src/main/java/example/Same.java"));
        assertTrue(result.contains("symbol: Same#first"));
        assertTrue(result.contains("type: SERVICE_METHOD"));
        assertTrue(result.contains("lines: 10-20"));
        assertTrue(result.endsWith("content:\nMARKER_ABC\n"));
        verify(chunkMapper).selectByRepoIdAndChunkId(R1, C1);
        assertThat(Arrays.stream(CodeChunkTools.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .doesNotContain(EmbeddingService.class.getName(), CodeSearchService.class.getName(),
                        CodeRagAnswerEvidenceService.class.getName(), CodeLlmEvidenceSelector.class.getName());
    }

    @Test
    void runtimeScopeMismatchRejectsBeforeRepositoryOrChunkAccess() {
        ToolPolicyRejectedException failure = assertThrows(ToolPolicyRejectedException.class,
                () -> tools.getCodeChunk(R2, C1, scoped(R1)));

        assertEquals("CODE_CHUNK_SCOPE_MISMATCH: requested repoId is outside the trusted runtime repository scope",
                failure.getMessage());
        verifyNoInteractions(repositoryMapper, chunkMapper);
    }

    @Test
    void wrongRepoDoesNotFindChunkOwnedByAnotherRepository() {
        when(repositoryMapper.selectById(R2)).thenReturn(repository(R2, "READY"));
        when(chunkMapper.selectByRepoIdAndChunkId(R2, C1)).thenReturn(null);

        ToolArgumentException failure = assertThrows(ToolArgumentException.class,
                () -> tools.getCodeChunk(R2, C1, scoped(R2)));

        assertTrue(failure.getMessage().startsWith("CODE_CHUNK_NOT_FOUND:"));
        verify(chunkMapper).selectByRepoIdAndChunkId(R2, C1);
        verify(chunkMapper, never()).selectByRepoIdAndChunkId(R1, C1);
    }

    @Test
    void unknownChunkFailsClosedWithoutFallback() {
        when(repositoryMapper.selectById(R1)).thenReturn(repository(R1, "READY"));
        when(chunkMapper.selectByRepoIdAndChunkId(R1, C2)).thenReturn(null);

        ToolArgumentException failure = assertThrows(ToolArgumentException.class,
                () -> tools.getCodeChunk(R1, C2, scoped(R1)));

        assertTrue(failure.getMessage().startsWith("CODE_CHUNK_NOT_FOUND:"));
        verify(chunkMapper).selectByRepoIdAndChunkId(R1, C2);
    }

    @Test
    void repositoryNotReadyRejectsBeforeChunkAccess() {
        when(repositoryMapper.selectById(R1)).thenReturn(repository(R1, "IMPORTING"));

        ToolPolicyRejectedException failure = assertThrows(ToolPolicyRejectedException.class,
                () -> tools.getCodeChunk(R1, C1, scoped(R1)));

        assertEquals("CODE_REPOSITORY_NOT_READY: repository status=IMPORTING", failure.getMessage());
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void missingRepositoryRejectsBeforeChunkAccess() {
        when(repositoryMapper.selectById(R1)).thenReturn(null);

        ToolPolicyRejectedException failure = assertThrows(ToolPolicyRejectedException.class,
                () -> tools.getCodeChunk(R1, C1, scoped(R1)));

        assertEquals("CODE_REPOSITORY_NOT_FOUND: trusted repository does not exist", failure.getMessage());
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void missingTrustedScopeRejectsWithoutDatabaseAccess() {
        ToolPolicyRejectedException failure = assertThrows(ToolPolicyRejectedException.class,
                () -> tools.getCodeChunk(R1, C1, new ToolContext(Map.of())));

        assertEquals("CODE_CHUNK_SCOPE_UNAVAILABLE: no trusted runtime repository scope", failure.getMessage());
        verifyNoInteractions(repositoryMapper, chunkMapper);
    }

    @Test
    void sameFileDifferentChunksReturnTheirOwnExactContent() {
        when(repositoryMapper.selectById(R1)).thenReturn(repository(R1, "READY"));
        when(chunkMapper.selectByRepoIdAndChunkId(R1, C1)).thenReturn(chunk(C1, "CONTENT_ONE"));
        when(chunkMapper.selectByRepoIdAndChunkId(R1, C2)).thenReturn(
                CodeChunkExactReadResult.builder()
                        .repoId(R1).chunkId(C2).filePath("src/main/java/example/Same.java")
                        .symbolName("Same#second").chunkType("SERVICE_METHOD")
                        .startLine(30).endLine(40).content("CONTENT_TWO").build());

        String first = tools.getCodeChunk(R1, C1, scoped(R1));
        String second = tools.getCodeChunk(R1, C2, scoped(R1));

        assertTrue(first.contains("chunkId: " + C1));
        assertTrue(first.endsWith("CONTENT_ONE\n"));
        assertTrue(second.contains("chunkId: " + C2));
        assertTrue(second.endsWith("CONTENT_TWO\n"));
    }

    private ToolContext scoped(String repoId) {
        return new ToolContext(Map.of(CodeChunkTools.TRUSTED_REPO_ID_TOOL_CONTEXT_KEY, repoId));
    }

    private CodeRepository repository(String repoId, String status) {
        return CodeRepository.builder().id(repoId).status(status).build();
    }

    private CodeChunkExactReadResult chunk(String chunkId, String content) {
        return CodeChunkExactReadResult.builder()
                .repoId(R1)
                .chunkId(chunkId)
                .filePath("src/main/java/example/Same.java")
                .symbolName("Same#first")
                .chunkType("SERVICE_METHOD")
                .startLine(10)
                .endLine(20)
                .content(content)
                .build();
    }
}
