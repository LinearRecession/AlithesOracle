package io.alithes.oracle.config;

import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Security constraints for URL validation and SSRF prevention ({@code oracle.security.*}).
 */
public record SecurityProperties(
        @DefaultValue("false") boolean allowPrivateNetworkUrls,
        @DefaultValue("2048") int maxUrlLength
) {
    @ConstructorBinding
    public SecurityProperties {
        PropertyAssertions.requirePositive(maxUrlLength, "oracle.security.max-url-length");
    }

    public SecurityProperties(boolean allowPrivateNetworkUrls) {
        this(allowPrivateNetworkUrls, 2048);
    }

    public static SecurityProperties defaults() {
        return new SecurityProperties(false, 2048);
    }
}
