package io.alithes.oracle.db;

import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.EvidenceStage;
import io.alithes.oracle.domain.FetchStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC repository for {@link Evidence} rows in the {@code evidence} table.
 */
@Repository
public class EvidenceRepository {

    private static final String SELECT_COLUMNS = """
            id, question_id, url, normalized_url, domain, title, seen_at, published_at,
            language, source_country, stage, fetch_status, headline_score,
            content_sha256, excerpt, simhash, created_at
            """;

    private final JdbcClient jdbcClient;

    public EvidenceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    /**
     * Inserts the evidence row if no row already exists for {@code (questionId, normalizedUrl)}.
     * Checks existence first so callers inside a PostgreSQL transaction never suffer an aborted
     * transaction from a unique-constraint violation, while still catching {@link DuplicateKeyException}
     * for concurrent races.
     */
    public boolean insertIfAbsent(Evidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        boolean exists = jdbcClient.sql("""
                SELECT id
                FROM evidence
                WHERE question_id = :questionId AND normalized_url = :normalizedUrl
                LIMIT 1
                """)
                .param("questionId", evidence.questionId().toString(), Types.VARCHAR)
                .param("normalizedUrl", evidence.normalizedUrl(), Types.VARCHAR)
                .query(String.class)
                .optional()
                .isPresent();
        if (exists) {
            return false;
        }

        try {
            jdbcClient.sql("""
                    INSERT INTO evidence (
                        id, question_id, url, normalized_url, domain, title, seen_at, published_at,
                        language, source_country, stage, fetch_status, headline_score,
                        content_sha256, excerpt, simhash, created_at
                    ) VALUES (
                        :id, :questionId, :url, :normalizedUrl, :domain, :title, :seenAt, :publishedAt,
                        :language, :sourceCountry, :stage, :fetchStatus, :headlineScore,
                        :contentSha256, :excerpt, :simhash, :createdAt
                    )
                    """)
                    .param("id", evidence.id().toString(), Types.VARCHAR)
                    .param("questionId", evidence.questionId().toString(), Types.VARCHAR)
                    .param("url", evidence.url(), Types.VARCHAR)
                    .param("normalizedUrl", evidence.normalizedUrl(), Types.VARCHAR)
                    .param("domain", evidence.domain(), Types.VARCHAR)
                    .param("title", evidence.title(), Types.VARCHAR)
                    .param("seenAt", JdbcSupport.toTimestamp(evidence.seenAt()), Types.TIMESTAMP)
                    .param("publishedAt", JdbcSupport.toTimestamp(evidence.publishedAt()), Types.TIMESTAMP)
                    .param("language", evidence.language(), Types.VARCHAR)
                    .param("sourceCountry", evidence.sourceCountry(), Types.VARCHAR)
                    .param("stage", evidence.stage().name(), Types.VARCHAR)
                    .param("fetchStatus", evidence.fetchStatus().name(), Types.VARCHAR)
                    .param("headlineScore", evidence.headlineScore(), Types.DOUBLE)
                    .param("contentSha256", evidence.contentSha256(), Types.VARCHAR)
                    .param("excerpt", evidence.excerpt(), Types.VARCHAR)
                    .param("simhash", evidence.simhash(), Types.BIGINT)
                    .param("createdAt", JdbcSupport.toTimestamp(evidence.createdAt()), Types.TIMESTAMP)
                    .update();
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    public Optional<Evidence> findById(UUID id) {
        Objects.requireNonNull(id, "id");
        return jdbcClient.sql("SELECT " + SELECT_COLUMNS + " FROM evidence WHERE id = :id")
                .param("id", id.toString(), Types.VARCHAR)
                .query(this::mapRow)
                .optional();
    }

    public List<Evidence> findByQuestionId(UUID questionId) {
        Objects.requireNonNull(questionId, "questionId");
        return jdbcClient.sql("""
                SELECT %s FROM evidence
                WHERE question_id = :questionId
                ORDER BY seen_at DESC, created_at ASC
                """.formatted(SELECT_COLUMNS))
                .param("questionId", questionId.toString(), Types.VARCHAR)
                .query(this::mapRow)
                .list();
    }

    public List<Evidence> findByQuestionIdAndStage(UUID questionId, EvidenceStage stage) {
        Objects.requireNonNull(questionId, "questionId");
        Objects.requireNonNull(stage, "stage");
        return jdbcClient.sql("""
                SELECT %s FROM evidence
                WHERE question_id = :questionId AND stage = :stage
                ORDER BY seen_at DESC, created_at ASC
                """.formatted(SELECT_COLUMNS))
                .param("questionId", questionId.toString(), Types.VARCHAR)
                .param("stage", stage.name(), Types.VARCHAR)
                .query(this::mapRow)
                .list();
    }

    public List<Evidence> findByQuestionIdAndStageLimit(UUID questionId, EvidenceStage stage, int limit) {
        Objects.requireNonNull(questionId, "questionId");
        Objects.requireNonNull(stage, "stage");
        if (limit <= 0) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT %s FROM evidence
                WHERE question_id = :questionId AND stage = :stage
                ORDER BY seen_at DESC, created_at ASC
                LIMIT :limit
                """.formatted(SELECT_COLUMNS))
                .param("questionId", questionId.toString(), Types.VARCHAR)
                .param("stage", stage.name(), Types.VARCHAR)
                .param("limit", limit, Types.INTEGER)
                .query(this::mapRow)
                .list();
    }

    public void updateHeadlineResult(UUID id, double headlineScore, EvidenceStage stage) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stage, "stage");
        jdbcClient.sql("""
                UPDATE evidence
                SET headline_score = :headlineScore,
                    stage = :stage
                WHERE id = :id
                """)
                .param("id", id.toString(), Types.VARCHAR)
                .param("headlineScore", headlineScore, Types.DOUBLE)
                .param("stage", stage.name(), Types.VARCHAR)
                .update();
    }

    public void updateFetchedContent(
            UUID id,
            FetchStatus fetchStatus,
            Instant publishedAt,
            String contentSha256,
            String excerpt,
            long simhash
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fetchStatus, "fetchStatus");
        jdbcClient.sql("""
                UPDATE evidence
                SET fetch_status = :fetchStatus,
                    published_at = :publishedAt,
                    content_sha256 = :contentSha256,
                    excerpt = :excerpt,
                    simhash = :simhash
                WHERE id = :id
                """)
                .param("id", id.toString(), Types.VARCHAR)
                .param("fetchStatus", fetchStatus.name(), Types.VARCHAR)
                .param("publishedAt", JdbcSupport.toTimestamp(publishedAt), Types.TIMESTAMP)
                .param("contentSha256", contentSha256, Types.VARCHAR)
                .param("excerpt", excerpt, Types.VARCHAR)
                .param("simhash", simhash, Types.BIGINT)
                .update();
    }

    public void updateStage(UUID id, EvidenceStage stage) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stage, "stage");
        jdbcClient.sql("""
                UPDATE evidence
                SET stage = :stage
                WHERE id = :id
                """)
                .param("id", id.toString(), Types.VARCHAR)
                .param("stage", stage.name(), Types.VARCHAR)
                .update();
    }

    private Evidence mapRow(ResultSet rs, int rowNum) throws SQLException {
        double rawScore = rs.getDouble("headline_score");
        Double headlineScore = rs.wasNull() ? null : rawScore;
        return new Evidence(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("question_id")),
                rs.getString("url"),
                rs.getString("normalized_url"),
                rs.getString("domain"),
                rs.getString("title"),
                JdbcSupport.toInstant(rs.getTimestamp("seen_at")),
                JdbcSupport.toInstant(rs.getTimestamp("published_at")),
                rs.getString("language"),
                rs.getString("source_country"),
                EvidenceStage.valueOf(rs.getString("stage")),
                FetchStatus.valueOf(rs.getString("fetch_status")),
                headlineScore,
                rs.getString("content_sha256"),
                rs.getString("excerpt"),
                rs.getLong("simhash"),
                JdbcSupport.toInstant(rs.getTimestamp("created_at"))
        );
    }
}
