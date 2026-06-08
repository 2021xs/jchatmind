package com.kama.jchatmind.exception;

import com.kama.jchatmind.model.response.ImportCodeRepositoryResponse;
import lombok.Getter;

@Getter
public class CodeRepositoryImportException extends RuntimeException {
    private final ImportCodeRepositoryResponse response;

    public CodeRepositoryImportException(String message, Throwable cause, ImportCodeRepositoryResponse response) {
        super(message, cause);
        this.response = response;
    }
}
