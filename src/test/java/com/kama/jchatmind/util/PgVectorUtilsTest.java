package com.kama.jchatmind.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PgVectorUtilsTest {

    @Test
    void preservesPgVectorLiteralFormat() {
        assertEquals("[1.0,-2.5,0.0]", PgVectorUtils.toLiteral(new float[]{1.0f, -2.5f, 0.0f}));
        assertEquals("[]", PgVectorUtils.toLiteral(new float[0]));
    }
}
