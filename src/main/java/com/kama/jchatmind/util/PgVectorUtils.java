package com.kama.jchatmind.util;

public final class PgVectorUtils {

    private PgVectorUtils() {
    }

    public static String toLiteral(float[] values) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            literal.append(values[i]);
            if (i < values.length - 1) {
                literal.append(",");
            }
        }
        literal.append("]");
        return literal.toString();
    }
}
