package io.alithes.oracle.client;

import java.util.List;

/**
 * Strategy interface for targeted supplemental evidence discovery alongside primary news search.
 */
public interface SupplementalEvidenceSource {

    List<GdeltArticle> collect(String questionText);
}
