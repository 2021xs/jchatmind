package com.kama.jchatmind.integration.feishu;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "jchatmind.feishu")
public class FeishuProperties {
    private boolean enabled;
    private String appId;
    private String appSecret;
    private String verificationToken;
    private String encryptKey;
}
