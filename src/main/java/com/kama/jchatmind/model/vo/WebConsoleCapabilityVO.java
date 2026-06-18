package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class WebConsoleCapabilityVO {
    private String key;
    private String label;
    private boolean enabled;
    private List<String> tools;
    private String description;
    private String reason;
}
