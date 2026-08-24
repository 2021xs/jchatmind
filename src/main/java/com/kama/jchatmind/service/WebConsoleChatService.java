package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.WebConsoleChatSendRequest;
import com.kama.jchatmind.model.response.WebConsoleChatSendResponse;

public interface WebConsoleChatService {
    WebConsoleChatSendResponse send(WebConsoleChatSendRequest request);
}
