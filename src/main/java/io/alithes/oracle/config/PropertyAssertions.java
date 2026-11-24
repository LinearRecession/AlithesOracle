package io.alithes.oracle.config;

/**
 * Shared validation helpers for {@code oracle.*} configuration records.
 */
final class PropertyAssertions {

    private PropertyAssertions() {
    }

    static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be > 0, got " + value);
        }
    }

    static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
    }

    static void requirePositiveDouble(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and > 0, got " + value);
        }
    }

    static void requireNonNegativeDouble(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and >= 0, got " + value);
        }
    }

    static void requireProbability(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0.0, 1.0], got " + value);
        }
    }

    static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
