package io.alithes.oracle.util;

import java.util.Locale;

/**
 * 64-bit SimHash for near-duplicate syndication detection.
 */
public final class SimHash {

    private SimHash() {
    }

    public static long compute(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        int[] bits = new int[64];
        String[] tokens = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        for (String token : tokens) {
            if (token.length() < 3) {
                continue;
            }
            long h = fnv1a64(token);
            for (int b = 0; b < 64; b++) {
                if (((h >>> b) & 1L) != 0L) {
                    bits[b]++;
                } else {
                    bits[b]--;
                }
            }
        }
        long result = 0L;
        for (int b = 0; b < 64; b++) {
            if (bits[b] > 0) {
                result |= (1L << b);
            }
        }
        return result;
    }

    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
