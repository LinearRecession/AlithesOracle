package io.alithes.oracle.client;

import java.util.function.BooleanSupplier;

/**
 * Ordered fallback news-source entry evaluated in a {@link FallbackNewsSource} chain.
 */
record FallbackProvider(String name, BooleanSupplier enabled, NewsSource source) {
}
