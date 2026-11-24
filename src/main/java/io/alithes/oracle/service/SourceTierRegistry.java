package io.alithes.oracle.service;

import io.alithes.oracle.util.UrlNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Maps news source domains to credibility tier weights loaded from {@code source-tiers.properties}.
 */
@Component
public class SourceTierRegistry {

    public static final double DEFAULT_WEIGHT = 0.3;
    private static final String RESOURCE_PATH = "/source-tiers.properties";

    private final Map<String, Double> domainWeights;

    @Autowired
    public SourceTierRegistry() {
        this(loadDefaultProperties());
    }

    /**
     * Creates a registry from an explicit map of domain weights (primarily for unit tests).
     */
    public SourceTierRegistry(Map<String, Double> domainWeights) {
        if (domainWeights == null || domainWeights.isEmpty()) {
            this.domainWeights = Map.of();
        } else {
            Map<String, Double> normalized = new HashMap<>(domainWeights.size());
            for (Map.Entry<String, Double> entry : domainWeights.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String key = stripHost(entry.getKey());
                if (!key.isEmpty()) {
                    normalized.put(key, entry.getValue());
                }
            }
            this.domainWeights = Map.copyOf(normalized);
        }
    }

    /**
     * Returns the credibility weight for a domain, checking exact match and one-label subdomain fallback.
     */
    public double weightFor(String domain) {
        String normalized = stripHost(domain);
        if (normalized.isEmpty()) {
            return DEFAULT_WEIGHT;
        }
        Double exact = domainWeights.get(normalized);
        if (exact != null) {
            return exact;
        }
        String parent = stripLeftmostLabel(normalized);
        if (!parent.isEmpty()) {
            Double parentWeight = domainWeights.get(parent);
            if (parentWeight != null) {
                return parentWeight;
            }
        }
        return DEFAULT_WEIGHT;
    }

    /**
     * Returns the canonical publisher domain for clustering (stripping {@code www.}/{@code m.} and
     * collapsing known publisher subdomains to their matched parent tier entry).
     */
    public String canonicalDomain(String domain) {
        String normalized = stripHost(domain);
        if (normalized.isEmpty()) {
            return "";
        }
        if (domainWeights.containsKey(normalized)) {
            return normalized;
        }
        String parent = stripLeftmostLabel(normalized);
        if (!parent.isEmpty() && domainWeights.containsKey(parent)) {
            return parent;
        }
        return normalized;
    }

    private static Map<String, Double> loadDefaultProperties() {
        Properties props = new Properties();
        try (InputStream in = SourceTierRegistry.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource not found: " + RESOURCE_PATH);
            }
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + RESOURCE_PATH, e);
        }
        Map<String, Double> loaded = new HashMap<>();
        for (String name : props.stringPropertyNames()) {
            String value = props.getProperty(name);
            if (value == null || value.isBlank()) {
                continue;
            }
            loaded.put(name, Double.parseDouble(value.trim()));
        }
        return loaded;
    }

    private static String stripHost(String domain) {
        return UrlNormalizer.stripHostPrefixes(domain);
    }

    private static String stripLeftmostLabel(String host) {
        int dot = host.indexOf('.');
        if (dot <= 0 || dot >= host.length() - 1) {
            return "";
        }
        return host.substring(dot + 1);
    }
}
