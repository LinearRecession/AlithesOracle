package io.alithes.oracle.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic URL canonicalization for evidence deduplication.
 */
public final class UrlNormalizer {

    private static final Set<String> DROPPED_QUERY_KEYS = Set.of(
            "amp",
            "fbclid",
            "gclid",
            "ref",
            "source",
            "outputtype"
    );

    private UrlNormalizer() {
    }

    public static String normalize(String rawUrl) {
        if (rawUrl == null) {
            return "";
        }
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        final URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            return trimmed.toLowerCase(Locale.ROOT);
        }

        String rawScheme = uri.getScheme();
        String rawHost = uri.getHost();
        if (rawScheme == null || rawHost == null) {
            return trimmed.toLowerCase(Locale.ROOT);
        }

        String scheme = rawScheme.toLowerCase(Locale.ROOT);
        String host = stripHostPrefixes(rawHost.toLowerCase(Locale.ROOT));

        int port = uri.getPort();
        boolean includePort = port != -1
                && !(("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443));

        String path = uri.getRawPath();
        if (path == null) {
            path = "";
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String sortedQuery = filterAndSortQuery(uri.getRawQuery());

        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (includePort) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (!sortedQuery.isEmpty()) {
            sb.append('?').append(sortedQuery);
        }
        return sb.toString();
    }

    public static String stripHostPrefixes(String host) {
        if (host == null) {
            return "";
        }
        String current = host.trim().toLowerCase(Locale.ROOT);
        while (current.endsWith(".")) {
            current = current.substring(0, current.length() - 1);
        }
        if (current.isEmpty()) {
            return "";
        }
        boolean changed;
        do {
            changed = false;
            if (current.startsWith("www.") && current.indexOf('.', 4) != -1) {
                current = current.substring(4);
                changed = true;
            } else if (current.startsWith("m.") && current.indexOf('.', 2) != -1) {
                current = current.substring(2);
                changed = true;
            }
        } while (changed);
        return current;
    }

    private static String filterAndSortQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        String[] parts = rawQuery.split("&");
        List<String> kept = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String rawKey = (eq >= 0) ? part.substring(0, eq) : part;
            String key = decodeQuietly(rawKey).toLowerCase(Locale.ROOT);
            if (key.startsWith("utm_") || DROPPED_QUERY_KEYS.contains(key)) {
                continue;
            }
            kept.add(part);
        }
        if (kept.isEmpty()) {
            return "";
        }
        Collections.sort(kept);
        return String.join("&", kept);
    }

    private static String decodeQuietly(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
