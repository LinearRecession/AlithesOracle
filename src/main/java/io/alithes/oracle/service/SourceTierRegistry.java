package io.alithes.oracle.service;

import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.config.ScoringProperties;
import io.alithes.oracle.util.UrlNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Maps news source domains to credibility tier weights loaded from configurable classpath properties.
 */
@Component
public class SourceTierRegistry {

    public static final double DEFAULT_WEIGHT = 0.3;

    private final Map<String, Double> domainWeights;
    private final double defaultWeight;

    @Autowired
    public SourceTierRegistry(OracleProperties props) {
        this(Objects.requireNonNull(props, "props must not be null").scoring());
    }

    public SourceTierRegistry() {
        this(ScoringProperties.defaults());
    }

    private SourceTierRegistry(ScoringProperties scoring) {
        this(loadProperties(scoring.tiersResourcePath()), scoring.defaultTierWeight());
    }

    /**
     * Creates a registry from an explicit map of domain weights (primarily for unit tests).
     */
    public SourceTierRegistry(Map<String, Double> domainWeights) {
        this(domainWeights, ScoringProperties.defaults().defaultTierWeight());
    }

    public SourceTierRegistry(Map<String, Double> domainWeights, double defaultWeight) {
        this.defaultWeight = defaultWeight;
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
            return defaultWeight;
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
        return defaultWeight;
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

    private static Map<String, Double> loadProperties(String resourcePath) {
        String path = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        Properties props = new Properties();
        try (InputStream in = SourceTierRegistry.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource not found: " + path);
            }
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + path, e);
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
