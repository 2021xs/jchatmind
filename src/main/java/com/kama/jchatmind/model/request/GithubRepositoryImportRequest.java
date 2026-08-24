package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class GithubRepositoryImportRequest {
    private String url;
    private String name;
}
