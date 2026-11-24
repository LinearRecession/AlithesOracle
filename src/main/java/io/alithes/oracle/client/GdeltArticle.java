package io.alithes.oracle.client;

import java.time.Instant;

/**
 * Article metadata returned by the GDELT DOC 2.0 API.
 */
public record GdeltArticle(
        String url,
        String title,
        Instant seenDate,
        String domain,
        String language,
        String sourceCountry
) {
}
