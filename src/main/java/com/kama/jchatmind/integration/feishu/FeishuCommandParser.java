package com.kama.jchatmind.integration.feishu;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class FeishuCommandParser {

    public boolean isHelpCommand(String text) {
        return StringUtils.hasText(text) && text.trim().startsWith("/help");
    }
}
