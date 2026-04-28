package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.request.ImportCodeRepositoryRequest;
import com.kama.jchatmind.model.response.GetCodeRepositoriesResponse;
import com.kama.jchatmind.model.response.ImportCodeRepositoryResponse;
import com.kama.jchatmind.model.response.SearchCodeRepositoryResponse;
import com.kama.jchatmind.service.CodeRepositoryService;
import com.kama.jchatmind.service.CodeSearchService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class CodeRepositoryController {
    private final CodeRepositoryService codeRepositoryService;
    private final CodeSearchService codeSearchService;

    @PostMapping("/code-repositories/import")
    public ApiResponse<ImportCodeRepositoryResponse> importRepository(@RequestBody ImportCodeRepositoryRequest request) {
        return ApiResponse.success(codeRepositoryService.importRepository(request));
    }

    @GetMapping("/code-repositories")
    public ApiResponse<GetCodeRepositoriesResponse> getRepositories() {
        return ApiResponse.success(codeRepositoryService.getRepositories());
    }

    @GetMapping("/code-repositories/{repoId}/search")
    public ApiResponse<SearchCodeRepositoryResponse> search(
            @PathVariable String repoId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") Integer topK) {
        List<CodeSearchResult> results = codeSearchService.search(repoId, query, topK == null ? 5 : topK);
        return ApiResponse.success(SearchCodeRepositoryResponse.builder()
                .results(results.toArray(new CodeSearchResult[0]))
                .build());
    }
}
