package com.shortener.util;

import java.util.concurrent.ThreadLocalRandom;

public final class Base62 {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private Base62() {
    }

    /** Random code of the given length; 62^7 ≈ 3.5 trillion combinations for the default length. */
    public static String randomCode(int length) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[rnd.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
        if (value == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            sb.append(ALPHABET[(int) (value % 62)]);
            value /= 62;
        }
        return sb.reverse().toString();
    }
}
