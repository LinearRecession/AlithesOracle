package io.alithes.oracle.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class SafeUrlValidatorTest {

    private static SafeUrlValidator strictWithFixedIp(int... octets) {
        byte[] bytes = new byte[octets.length];
        for (int i = 0; i < octets.length; i++) {
            bytes[i] = (byte) octets[i];
        }
        return new SafeUrlValidator(false, host -> {
            try {
                return new InetAddress[] { InetAddress.getByAddress(host, bytes) };
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(e);
            }
        });
    }

    @Test
    void blocksLoopbackLinkLocalPrivateCgnatAndUnsafeSchemesInStrictMode() {
        SafeUrlValidator validator = new SafeUrlValidator(false);

        assertThatThrownBy(() -> validator.validateAndParse("http://127.0.0.1/admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("http://localhost:8080/v1/questions"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("http://10.0.0.1/internal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("http://172.16.0.1/internal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("http://192.168.1.1/router"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("http://100.64.0.1/cgnat"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("http://[::1]/loopback"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("ftp://example.com/file"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("http://user:pass@example.com/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateAndParse("https://example.com/" + "a".repeat(2050)))
                .isInstanceOf(IllegalArgumentException.class);

        SafeUrlValidator ipv6UniqueLocal = strictWithFixedIp(
                0xfc, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
        assertThatThrownBy(() -> ipv6UniqueLocal.validateAndParse("https://internal.example/hook"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsPublicIpTargetsInStrictModeAndLocalhostOnlyWhenExplicitlyEnabled() {
        SafeUrlValidator strictPublic = strictWithFixedIp(93, 184, 216, 34);
        URI publicUri = strictPublic.validateAndParse("https://reuters.com/world/article");
        assertThat(publicUri.getHost()).isEqualTo("reuters.com");

        SafeUrlValidator permissive = new SafeUrlValidator(true);
        URI localUri = permissive.validateAndParse("http://localhost:8080/webhook");
        assertThat(localUri.getHost()).isEqualTo("localhost");
        assertThatThrownBy(() -> permissive.validateAndParse("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> permissive.validateAndParse("http://user:pass@localhost:8080/webhook"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
