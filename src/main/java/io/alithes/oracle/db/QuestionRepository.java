package io.alithes.oracle.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC repository for {@link Question} rows in the {@code questions} table.
 */
@Repository
public class QuestionRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private static final String SELECT_COLUMNS = """
            id, text, resolution_criteria, window_start, deadline, grace_hours,
            status, status_reason, search_queries_json, webhook_url,
            last_scan_end, created_at, updated_at
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public QuestionRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Transactional
    public void save(Question question) {
        Objects.requireNonNull(question, "question");
        int updated = bindAll(jdbcClient.sql("""
                UPDATE questions
                SET text = :text,
                    resolution_criteria = :resolutionCriteria,
                    window_start = :windowStart,
                    deadline = :deadline,
                    grace_hours = :graceHours,
                    status = :status,
                    status_reason = :statusReason,
                    search_queries_json = :searchQueriesJson,
                    webhook_url = :webhookUrl,
                    last_scan_end = :lastScanEnd,
                    created_at = :createdAt,
                    updated_at = :updatedAt
                WHERE id = :id
                """), question).update();

        if (updated == 0) {
            bindAll(jdbcClient.sql("""
                    INSERT INTO questions (
                        id, text, resolution_criteria, window_start, deadline, grace_hours,
                        status, status_reason, search_queries_json, webhook_url,
                        last_scan_end, created_at, updated_at
                    ) VALUES (
                        :id, :text, :resolutionCriteria, :windowStart, :deadline, :graceHours,
                        :status, :statusReason, :searchQueriesJson, :webhookUrl,
                        :lastScanEnd, :createdAt, :updatedAt
                    )
                    """), question).update();
        }
    }

    public Optional<Question> findById(UUID id) {
        Objects.requireNonNull(id, "id");
        return jdbcClient.sql("SELECT " + SELECT_COLUMNS + " FROM questions WHERE id = :id")
                .param("id", id.toString(), Types.VARCHAR)
                .query(this::mapRow)
                .optional();
    }

    public List<Question> findByStatus(QuestionStatus status) {
        Objects.requireNonNull(status, "status");
        return jdbcClient.sql("SELECT " + SELECT_COLUMNS + " FROM questions WHERE status = :status ORDER BY created_at ASC")
                .param("status", status.name(), Types.VARCHAR)
                .query(this::mapRow)
                .list();
    }

    public List<Question> findAll() {
        return jdbcClient.sql("SELECT " + SELECT_COLUMNS + " FROM questions ORDER BY created_at DESC")
                .query(this::mapRow)
                .list();
    }

    public List<Question> findByStatusOrderByCreatedAtDesc(QuestionStatus status) {
        Objects.requireNonNull(status, "status");
        return jdbcClient.sql("SELECT " + SELECT_COLUMNS + " FROM questions WHERE status = :status ORDER BY created_at DESC")
                .param("status", status.name(), Types.VARCHAR)
                .query(this::mapRow)
                .list();
    }

    public Map<QuestionStatus, Long> countByStatus() {
        EnumMap<QuestionStatus, Long> counts = new EnumMap<>(QuestionStatus.class);
        for (QuestionStatus status : QuestionStatus.values()) {
            counts.put(status, 0L);
        }
        return jdbcClient.sql("SELECT status, COUNT(*) AS cnt FROM questions GROUP BY status")
                .query(rs -> {
                    while (rs.next()) {
                        QuestionStatus status = QuestionStatus.valueOf(rs.getString("status"));
                        counts.put(status, rs.getLong("cnt"));
                    }
                    return Collections.unmodifiableMap(counts);
                });
    }

    public void updateStatus(UUID id, QuestionStatus status, String statusReason, Instant updatedAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
        jdbcClient.sql("""
                UPDATE questions
                SET status = :status,
                    status_reason = :statusReason,
                    updated_at = :updatedAt
                WHERE id = :id
                """)
                .param("id", id.toString(), Types.VARCHAR)
                .param("status", status.name(), Types.VARCHAR)
                .param("statusReason", statusReason, Types.VARCHAR)
                .param("updatedAt", JdbcSupport.toTimestamp(updatedAt), Types.TIMESTAMP)
                .update();
    }

    public void updateLastScanEnd(UUID id, Instant lastScanEnd, Instant updatedAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(updatedAt, "updatedAt");
        jdbcClient.sql("""
                UPDATE questions
                SET last_scan_end = :lastScanEnd,
                    updated_at = :updatedAt
                WHERE id = :id
                """)
                .param("id", id.toString(), Types.VARCHAR)
                .param("lastScanEnd", JdbcSupport.toTimestamp(lastScanEnd), Types.TIMESTAMP)
                .param("updatedAt", JdbcSupport.toTimestamp(updatedAt), Types.TIMESTAMP)
                .update();
    }

    private JdbcClient.StatementSpec bindAll(JdbcClient.StatementSpec spec, Question question) {
        return spec
                .param("id", question.id().toString(), Types.VARCHAR)
                .param("text", question.text(), Types.VARCHAR)
                .param("resolutionCriteria", question.resolutionCriteria(), Types.VARCHAR)
                .param("windowStart", JdbcSupport.toTimestamp(question.windowStart()), Types.TIMESTAMP)
                .param("deadline", JdbcSupport.toTimestamp(question.deadline()), Types.TIMESTAMP)
                .param("graceHours", question.graceHours(), Types.INTEGER)
                .param("status", question.status().name(), Types.VARCHAR)
                .param("statusReason", question.statusReason(), Types.VARCHAR)
                .param("searchQueriesJson", JdbcSupport.writeJsonList(
                        objectMapper, question.searchQueries(), "searchQueries"), Types.VARCHAR)
                .param("webhookUrl", question.webhookUrl(), Types.VARCHAR)
                .param("lastScanEnd", JdbcSupport.toTimestamp(question.lastScanEnd()), Types.TIMESTAMP)
                .param("createdAt", JdbcSupport.toTimestamp(question.createdAt()), Types.TIMESTAMP)
                .param("updatedAt", JdbcSupport.toTimestamp(question.updatedAt()), Types.TIMESTAMP);
    }

    private Question mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Question(
                UUID.fromString(rs.getString("id")),
                rs.getString("text"),
                rs.getString("resolution_criteria"),
                JdbcSupport.toInstant(rs.getTimestamp("window_start")),
                JdbcSupport.toInstant(rs.getTimestamp("deadline")),
                rs.getInt("grace_hours"),
                QuestionStatus.valueOf(rs.getString("status")),
                rs.getString("status_reason"),
                JdbcSupport.readJsonList(
                        objectMapper, rs.getString("search_queries_json"), STRING_LIST_TYPE, "search_queries_json"),
                rs.getString("webhook_url"),
                JdbcSupport.toInstant(rs.getTimestamp("last_scan_end")),
                JdbcSupport.toInstant(rs.getTimestamp("created_at")),
                JdbcSupport.toInstant(rs.getTimestamp("updated_at"))
        );
    }
}
