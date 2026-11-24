package io.alithes.oracle.db;

import io.alithes.oracle.domain.Assessment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC repository for {@link Assessment} rows in the {@code assessments} table.
 */
@Repository
public class AssessmentRepository {

    private static final String SELECT_COLUMNS = """
            id, evidence_id, question_id, model, p_relevant, p_stance_fits,
            p_confirms, p_planned, p_speculation, p_denies, stance_unstable,
            stance_confidence, p_fresh, attribution, injection_flag, input_tokens,
            request_sha256, response_json, created_at
            """;

    private final JdbcClient jdbcClient;

    public AssessmentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Transactional
    public void save(Assessment assessment) {
        Objects.requireNonNull(assessment, "assessment");
        int updated = bindAll(jdbcClient.sql("""
                UPDATE assessments
                SET id = :id,
                    evidence_id = :evidenceId,
                    question_id = :questionId,
                    model = :model,
                    p_relevant = :pRelevant,
                    p_stance_fits = :pStanceFits,
                    p_confirms = :pConfirms,
                    p_planned = :pPlanned,
                    p_speculation = :pSpeculation,
                    p_denies = :pDenies,
                    stance_unstable = :stanceUnstable,
                    stance_confidence = :stanceConfidence,
                    p_fresh = :pFresh,
                    attribution = :attribution,
                    injection_flag = :injectionFlag,
                    input_tokens = :inputTokens,
                    request_sha256 = :requestSha256,
                    response_json = :responseJson,
                    created_at = :createdAt
                WHERE id = :id OR evidence_id = :evidenceId
                """), assessment).update();

        if (updated == 0) {
            bindAll(jdbcClient.sql("""
                    INSERT INTO assessments (
                        id, evidence_id, question_id, model, p_relevant, p_stance_fits,
                        p_confirms, p_planned, p_speculation, p_denies, stance_unstable,
                        stance_confidence, p_fresh, attribution, injection_flag, input_tokens,
                        request_sha256, response_json, created_at
                    ) VALUES (
                        :id, :evidenceId, :questionId, :model, :pRelevant, :pStanceFits,
                        :pConfirms, :pPlanned, :pSpeculation, :pDenies, :stanceUnstable,
                        :stanceConfidence, :pFresh, :attribution, :injectionFlag, :inputTokens,
                        :requestSha256, :responseJson, :createdAt
                    )
                    """), assessment).update();
        }
    }

    public Optional<Assessment> findByEvidenceId(UUID evidenceId) {
        Objects.requireNonNull(evidenceId, "evidenceId");
        return jdbcClient.sql("SELECT " + SELECT_COLUMNS + " FROM assessments WHERE evidence_id = :evidenceId")
                .param("evidenceId", evidenceId.toString(), Types.VARCHAR)
                .query(this::mapRow)
                .optional();
    }

    public List<Assessment> findByQuestionId(UUID questionId) {
        Objects.requireNonNull(questionId, "questionId");
        return jdbcClient.sql("""
                SELECT %s FROM assessments
                WHERE question_id = :questionId
                ORDER BY created_at ASC
                """.formatted(SELECT_COLUMNS))
                .param("questionId", questionId.toString(), Types.VARCHAR)
                .query(this::mapRow)
                .list();
    }

    private JdbcClient.StatementSpec bindAll(JdbcClient.StatementSpec spec, Assessment assessment) {
        return spec
                .param("id", assessment.id().toString(), Types.VARCHAR)
                .param("evidenceId", assessment.evidenceId().toString(), Types.VARCHAR)
                .param("questionId", assessment.questionId().toString(), Types.VARCHAR)
                .param("model", assessment.model(), Types.VARCHAR)
                .param("pRelevant", assessment.pRelevant(), Types.DOUBLE)
                .param("pStanceFits", assessment.pStanceFits(), Types.DOUBLE)
                .param("pConfirms", assessment.pConfirms(), Types.DOUBLE)
                .param("pPlanned", assessment.pPlanned(), Types.DOUBLE)
                .param("pSpeculation", assessment.pSpeculation(), Types.DOUBLE)
                .param("pDenies", assessment.pDenies(), Types.DOUBLE)
                .param("stanceUnstable", assessment.stanceUnstable(), Types.BOOLEAN)
                .param("stanceConfidence", assessment.stanceConfidence(), Types.DOUBLE)
                .param("pFresh", assessment.pFresh(), Types.DOUBLE)
                .param("attribution", assessment.attribution(), Types.VARCHAR)
                .param("injectionFlag", assessment.injectionFlag(), Types.BOOLEAN)
                .param("inputTokens", assessment.inputTokens(), Types.INTEGER)
                .param("requestSha256", assessment.requestSha256(), Types.VARCHAR)
                .param("responseJson", assessment.responseJson(), Types.VARCHAR)
                .param("createdAt", JdbcSupport.toTimestamp(assessment.createdAt()), Types.TIMESTAMP);
    }

    private Assessment mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Assessment(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("evidence_id")),
                UUID.fromString(rs.getString("question_id")),
                rs.getString("model"),
                rs.getDouble("p_relevant"),
                rs.getDouble("p_stance_fits"),
                rs.getDouble("p_confirms"),
                rs.getDouble("p_planned"),
                rs.getDouble("p_speculation"),
                rs.getDouble("p_denies"),
                rs.getBoolean("stance_unstable"),
                rs.getDouble("stance_confidence"),
                rs.getDouble("p_fresh"),
                rs.getString("attribution"),
                rs.getBoolean("injection_flag"),
                rs.getInt("input_tokens"),
                rs.getString("request_sha256"),
                rs.getString("response_json"),
                JdbcSupport.toInstant(rs.getTimestamp("created_at"))
        );
    }
}
