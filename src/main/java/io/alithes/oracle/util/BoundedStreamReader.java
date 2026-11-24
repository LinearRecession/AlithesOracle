package io.alithes.oracle.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Bounded stream-reading helper preventing unbounded memory allocation on external HTTP responses.
 */
public final class BoundedStreamReader {

    private BoundedStreamReader() {
    }

    /**
     * Reads up to {@code maxBytes} from {@code inputStream} using {@code bufferSize}-byte chunks.
     */
    public static byte[] readCappedBytes(InputStream inputStream, int maxBytes, int bufferSize) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        if (maxBytes <= 0) {
            return new byte[0];
        }
        int effectiveBufferSize = Math.max(256, bufferSize);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 65_536));
        byte[] buffer = new byte[effectiveBufferSize];
        int remaining = maxBytes;
        while (remaining > 0) {
            int toRead = Math.min(buffer.length, remaining);
            int read = inputStream.read(buffer, 0, toRead);
            if (read == -1) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
    }
}
