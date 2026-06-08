package com.kama.jchatmind.exception;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.dto.ImportQualitySummary;
import com.kama.jchatmind.model.response.ImportCodeRepositoryResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void codeRepositoryImportExceptionReturnsFailureResponseWithSummaryData() {
        ImportQualitySummary summary = ImportQualitySummary.builder()
                .totalFiles(1)
                .scannedFiles(1)
                .parsedFiles(1)
                .failedFiles(1)
                .chunkCount(1)
                .embeddedChunkCount(0)
                .status("FAILED")
                .build();
        ImportCodeRepositoryResponse response = ImportCodeRepositoryResponse.builder()
                .repoId("repo-1")
                .message("代码库导入失败: embedding down")
                .importQualitySummary(summary)
                .build();

        ApiResponse<ImportCodeRepositoryResponse> apiResponse = handler.handleCodeRepositoryImportException(
                new CodeRepositoryImportException("代码库导入失败: embedding down",
                        new IllegalStateException("embedding down"), response));

        assertEquals(500, apiResponse.getCode());
        assertEquals("代码库导入失败: embedding down", apiResponse.getMessage());
        assertEquals("repo-1", apiResponse.getData().getRepoId());
        assertEquals("FAILED", apiResponse.getData().getImportQualitySummary().getStatus());
        assertEquals(1, apiResponse.getData().getImportQualitySummary().getFailedFiles());
    }
}
