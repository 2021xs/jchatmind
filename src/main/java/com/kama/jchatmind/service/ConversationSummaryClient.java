package com.kama.jchatmind.service;

public interface ConversationSummaryClient {
    String summarize(String model, String prompt);
}
