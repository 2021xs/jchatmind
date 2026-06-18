package com.kama.jchatmind.service;

import com.kama.jchatmind.model.response.GetWebConsoleCapabilitiesResponse;

import java.util.List;

public interface WebConsoleCapabilityService {
    String PROFILE = "WEB_CONSOLE_CODE_ASSISTANT_SAFE_FULL";

    List<String> safeFullOptionalToolNames();

    GetWebConsoleCapabilitiesResponse getCapabilities(String repoId, String model);

    String runtimeCapabilityContext(GetWebConsoleCapabilitiesResponse capabilities);
}
