package io.alithes.oracle.db;

import io.alithes.oracle.domain.ScanLog;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC repository for {@link ScanLog} rows in the {@code scan_logs} table.
 */
@Repository
public class ScanLogRepository {

    private static final String SELECT_COLUMNS = """
            id, question_id, started_at, finished_at, gdelt_ok,
            articles_found, new_evidence, error
            """;

    private final JdbcClient jdbcClient;

    public ScanLogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Transactional
    public void save(ScanLog scanLog) {
        Objects.requireNonNull(scanLog, "scanLog");
        int updated = bindAll(jdbcClient.sql("""
                UPDATE scan_logs
                SET question_id = :questionId,
                    started_at = :startedAt,
                    finished_at = :finishedAt,
                    gdelt_ok = :gdeltOk,
                    articles_found = :articlesFound,
                    new_evidence = :newEvidence,
                    error = :error
                WHERE id = :id
                """), scanLog).update();

        if (updated == 0) {
            bindAll(jdbcClient.sql("""
                    INSERT INTO scan_logs (
                        id, question_id, started_at, finished_at, gdelt_ok,
                        articles_found, new_evidence, error
                    ) VALUES (
                        :id, :questionId, :startedAt, :finishedAt, :gdeltOk,
                        :articlesFound, :newEvidence, :error
                    )
                    """), scanLog).update();
        }
    }

    public List<ScanLog> findByQuestionId(UUID questionId) {
        Objects.requireNonNull(questionId, "questionId");
        return jdbcClient.sql("""
                SELECT %s FROM scan_logs
                WHERE question_id = :questionId
                ORDER BY started_at ASC
                """.formatted(SELECT_COLUMNS))
                .param("questionId", questionId.toString(), Types.VARCHAR)
                .query(this::mapRow)
                .list();
    }

    private JdbcClient.StatementSpec bindAll(JdbcClient.StatementSpec spec, ScanLog scanLog) {
        return spec
                .param("id", scanLog.id().toString(), Types.VARCHAR)
                .param("questionId", scanLog.questionId().toString(), Types.VARCHAR)
                .param("startedAt", JdbcSupport.toTimestamp(scanLog.startedAt()), Types.TIMESTAMP)
                .param("finishedAt", JdbcSupport.toTimestamp(scanLog.finishedAt()), Types.TIMESTAMP)
                .param("gdeltOk", scanLog.gdeltOk(), Types.BOOLEAN)
                .param("articlesFound", scanLog.articlesFound(), Types.INTEGER)
                .param("newEvidence", scanLog.newEvidence(), Types.INTEGER)
                .param("error", scanLog.error(), Types.VARCHAR);
    }

    private ScanLog mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ScanLog(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("question_id")),
                JdbcSupport.toInstant(rs.getTimestamp("started_at")),
                JdbcSupport.toInstant(rs.getTimestamp("finished_at")),
                rs.getBoolean("gdelt_ok"),
                rs.getInt("articles_found"),
                rs.getInt("new_evidence"),
                rs.getString("error")
        );
    }
}
