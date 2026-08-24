package com.kama.jchatmind;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.config.location=classpath:/application-test.yaml")
@ActiveProfiles("test")
class JchatmindApplicationTests {

    @Test
    void contextLoads() {
    }
}
