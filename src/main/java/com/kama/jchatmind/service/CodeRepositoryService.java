package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.ImportCodeRepositoryRequest;
import com.kama.jchatmind.model.response.GetCodeRepositoriesResponse;
import com.kama.jchatmind.model.response.ImportCodeRepositoryResponse;

public interface CodeRepositoryService {
    ImportCodeRepositoryResponse importRepository(ImportCodeRepositoryRequest request);

    GetCodeRepositoriesResponse getRepositories();

    void deleteRepository(String repoId);
}
