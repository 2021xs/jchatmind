package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.ImportCodeRepositoryRequest;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.model.response.GetCodeRepositoriesResponse;
import com.kama.jchatmind.model.response.ImportCodeRepositoryResponse;

import java.nio.file.Path;

public interface CodeRepositoryService {
    ImportCodeRepositoryResponse importRepository(ImportCodeRepositoryRequest request);

    ImportCodeRepositoryResponse indexRepository(CodeRepository repository, Path rootPath);

    GetCodeRepositoriesResponse getRepositories();

    void deleteRepository(String repoId);
}
