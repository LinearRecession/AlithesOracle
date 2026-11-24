package io.alithes.oracle.client;

/**
 * Port for fetching and extracting article text from a URL.
 */
public interface ContentFetcher {

    FetchedArticle fetch(String url, String title);
}
