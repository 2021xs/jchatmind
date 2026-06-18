package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.vo.WebConsoleCapabilityVO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GetWebConsoleCapabilitiesResponse {
    private String assistant;
    private String profile;
    private String model;
    private String repoId;
    private List<WebConsoleCapabilityVO> capabilities;
    private List<String> notSupported;
}
