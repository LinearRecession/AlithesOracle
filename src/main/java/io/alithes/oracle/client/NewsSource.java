package io.alithes.oracle.client;

import java.time.Instant;
import java.util.List;

/**
 * Port for searching global news metadata within a time window.
 */
public interface NewsSource {

    List<GdeltArticle> search(String query, Instant from, Instant to);
}
