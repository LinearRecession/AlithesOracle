package io.alithes.oracle.domain;

import java.time.Instant;
import java.util.UUID;

public record Evidence(
        UUID id,
        UUID questionId,
        String url,
        String normalizedUrl,
        String domain,
        String title,
        Instant seenAt,
        Instant publishedAt,
        String language,
        String sourceCountry,
        EvidenceStage stage,
        FetchStatus fetchStatus,
        Double headlineScore,
        String contentSha256,
        String excerpt,
        long simhash,
        Instant createdAt
) {
    public Evidence withStage(EvidenceStage newStage) {
        return new Evidence(
                id,
                questionId,
                url,
                normalizedUrl,
                domain,
                title,
                seenAt,
                publishedAt,
                language,
                sourceCountry,
                newStage,
                fetchStatus,
                headlineScore,
                contentSha256,
                excerpt,
                simhash,
                createdAt
        );
    }

    public Evidence withHeadlineResult(double newHeadlineScore, EvidenceStage newStage) {
        return new Evidence(
                id,
                questionId,
                url,
                normalizedUrl,
                domain,
                title,
                seenAt,
                publishedAt,
                language,
                sourceCountry,
                newStage,
                fetchStatus,
                newHeadlineScore,
                contentSha256,
                excerpt,
                simhash,
                createdAt
        );
    }

    public Evidence withFetchedContent(
            FetchStatus newFetchStatus,
            Instant newPublishedAt,
            String newContentSha256,
            String newExcerpt,
            long newSimhash
    ) {
        return new Evidence(
                id,
                questionId,
                url,
                normalizedUrl,
                domain,
                title,
                seenAt,
                newPublishedAt,
                language,
                sourceCountry,
                stage,
                newFetchStatus,
                headlineScore,
                newContentSha256,
                newExcerpt,
                newSimhash,
                createdAt
        );
    }
}
