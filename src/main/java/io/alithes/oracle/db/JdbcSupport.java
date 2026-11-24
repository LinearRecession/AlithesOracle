package io.alithes.oracle.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Shared JDBC timestamp and JSON serialization helpers for repositories.
 */
final class JdbcSupport {

    private JdbcSupport() {
    }

    static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }

    static String writeJsonList(ObjectMapper objectMapper, List<?> values, String fieldName) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + fieldName, e);
        }
    }

    static <T> List<T> readJsonList(
            ObjectMapper objectMapper,
            String json,
            TypeReference<List<T>> typeRef,
            String columnName
    ) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<T> parsed = objectMapper.readValue(json, typeRef);
            return parsed == null ? List.of() : parsed;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize " + columnName, e);
        }
    }
}
