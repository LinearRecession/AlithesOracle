package io.alithes.oracle.util;

import io.alithes.oracle.config.OracleProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Validates external HTTP(S) URLs against Server-Side Request Forgery (SSRF) targets.
 */
@Component
public class SafeUrlValidator {

    private static final int MAX_URL_LENGTH = 2048;

    private final boolean allowPrivateNetworkUrls;
    private final Function<String, InetAddress[]> dnsResolver;

    @Autowired
    public SafeUrlValidator(OracleProperties props) {
        this(Objects.requireNonNull(props, "props must not be null").security().allowPrivateNetworkUrls());
    }

    public SafeUrlValidator(boolean allowPrivateNetworkUrls) {
        this(allowPrivateNetworkUrls, SafeUrlValidator::resolveAll);
    }

    SafeUrlValidator(boolean allowPrivateNetworkUrls, Function<String, InetAddress[]> dnsResolver) {
        this.allowPrivateNetworkUrls = allowPrivateNetworkUrls;
        this.dnsResolver = Objects.requireNonNull(dnsResolver, "dnsResolver must not be null");
    }

    /**
     * Parses {@code rawUrl} and validates that it points to a safe HTTP(S) target.
     */
    public URI validateAndParse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("URL must not be null or blank");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("URL exceeds maximum length of " + MAX_URL_LENGTH);
        }
        final URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL syntax", e);
        }
        validate(uri);
        return uri;
    }

    /**
     * Validates an already-parsed {@link URI} against scheme, userinfo, port, and IP-range rules.
     */
    public void validate(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URI must not be null");
        }
        String raw = uri.toString();
        if (raw.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("URL exceeds maximum length of " + MAX_URL_LENGTH);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("URL scheme must be http or https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("URL must not contain userinfo credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must contain a valid host");
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) {
            throw new IllegalArgumentException("URL contains an invalid port");
        }

        if (allowPrivateNetworkUrls) {
            return;
        }

        String lookupHost = stripIpv6Brackets(host);
        final InetAddress[] addresses;
        try {
            addresses = dnsResolver.apply(lookupHost);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Failed to resolve host: " + lookupHost, e);
        }

        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("Host resolved to no IP addresses: " + lookupHost);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("Target host resolves to a non-public IP address: " + lookupHost);
            }
        }
    }

    private static InetAddress[] resolveAll(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Unable to resolve host: " + host, e);
        }
    }

    private static String stripIpv6Brackets(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static boolean isBlockedAddress(InetAddress addr) {
        if (addr == null
                || addr.isLoopbackAddress()
                || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 0 || b0 == 127 || b0 == 10) {
                return true;
            }
            if (b0 == 100 && (b1 & 0xC0) == 64) {
                return true; // 100.64.0.0/10 CGNAT
            }
            if (b0 == 172 && b1 >= 16 && b1 <= 31) {
                return true;
            }
            if (b0 == 192 && b1 == 168) {
                return true;
            }
            if (b0 == 169 && b1 == 254) {
                return true;
            }
        } else if (bytes.length == 16) {
            int b0 = bytes[0] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) {
                return true; // fc00::/7 Unique Local IPv6
            }
        }
        return false;
    }
}
