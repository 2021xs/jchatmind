package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.SelectorModelResponse;

public interface LlmSelectorClient {

    SelectorModelResponse call(String prompt);
}
