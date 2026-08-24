package com.kama.jchatmind;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.config.location=classpath:/application-test.yaml")
@ActiveProfiles("test")
public class JChatMindTests {
}
