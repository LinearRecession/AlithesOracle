package io.alithes.oracle.util;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared cryptographic hashing and HMAC signing helpers.
 */
public final class CryptoUtil {

    private static final String SHA_256 = "SHA-256";
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private CryptoUtil() {
    }

    public static String sha256Hex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        try {
            byte[] digest = MessageDigest.getInstance(SHA_256).digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    public static String sha256Hex(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacSha256Hex(String secret, byte[] payload) {
        Objects.requireNonNull(secret, "secret must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256);
            mac.init(keySpec);
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", e);
        }
    }
}
