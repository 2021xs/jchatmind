package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.GithubRepositoryImportRequest;
import com.kama.jchatmind.model.response.ImportCodeRepositoryResponse;

public interface GithubRepositoryImportService {
    ImportCodeRepositoryResponse importRepository(GithubRepositoryImportRequest request);
}
