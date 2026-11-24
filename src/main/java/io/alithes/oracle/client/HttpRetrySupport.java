package io.alithes.oracle.client;

/**
 * Shared exponential backoff and interrupt-safe sleep helpers for HTTP clients.
 */
public final class HttpRetrySupport {

    public static final long DEFAULT_MAX_BACKOFF_MS = 60_000L;

    private HttpRetrySupport() {
    }

    public static long exponentialBackoffMs(long baseDelayMs, int attempt) {
        return exponentialBackoffMs(baseDelayMs, attempt, DEFAULT_MAX_BACKOFF_MS);
    }

    public static long exponentialBackoffMs(long baseDelayMs, int attempt, long maxDelayMs) {
        long base = Math.max(0L, baseDelayMs);
        if (base == 0L || maxDelayMs <= 0L) {
            return 0L;
        }
        int shift = Math.min(30, Math.max(0, attempt - 1));
        long multiplier = 1L << shift;
        long delay = (base > maxDelayMs / multiplier) ? maxDelayMs : base * multiplier;
        return Math.min(maxDelayMs, delay);
    }

    public static void sleepInterruptibly(long delayMs) throws InterruptedException {
        if (delayMs > 0L) {
            Thread.sleep(delayMs);
        }
    }
}
