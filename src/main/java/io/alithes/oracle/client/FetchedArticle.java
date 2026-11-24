package io.alithes.oracle.client;

import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.util.CryptoUtil;
import io.alithes.oracle.util.SimHash;
import java.time.Instant;
import java.util.List;

/**
 * Extracted article text and fingerprint metadata returned by {@link ArticleFetcher}.
 */
public record FetchedArticle(
        FetchStatus status,
        List<String> paragraphs,
        String joinedText,
        Instant publishedAt,
        String contentSha256,
        String excerpt,
        long simhash
) {
    private static final int MAX_EXCERPT_CHARS = 2000;

    public FetchedArticle {
        paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
        joinedText = joinedText == null ? "" : joinedText;
    }

    public static FetchedArticle titleOnly(String title) {
        String safeTitle = title == null ? "" : title.trim();
        String excerpt = safeTitle.length() > MAX_EXCERPT_CHARS
                ? safeTitle.substring(0, MAX_EXCERPT_CHARS)
                : safeTitle;
        return new FetchedArticle(
                FetchStatus.TITLE_ONLY,
                List.of(),
                safeTitle,
                null,
                CryptoUtil.sha256Hex(safeTitle),
                excerpt,
                SimHash.compute(safeTitle)
        );
    }
}
