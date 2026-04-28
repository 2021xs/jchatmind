package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class ImportCodeRepositoryRequest {
    private String name;
    private String rootPath;
}
