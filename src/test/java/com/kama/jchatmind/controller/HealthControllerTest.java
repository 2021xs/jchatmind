package com.kama.jchatmind.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void healthIsAvailableWithoutDevProfile() {
        assertThat(new HealthController().health()).isEqualTo("ok");
    }
}
