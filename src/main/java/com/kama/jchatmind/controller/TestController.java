package com.kama.jchatmind.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
public class TestController {
    @GetMapping("/sse-test")
    public String sseTest() {
        return "ok";
    }
}
