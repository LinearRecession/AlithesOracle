package io.alithes.oracle.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
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
 * JDBC repository for {@link Resolution} rows in the {@code resolutions} table.
 */
@Repository
public class ResolutionRepository {

    private static final TypeReference<List<UUID>> UUID_LIST_TYPE = new TypeReference<>() {};

    private static final String SELECT_COLUMNS = """
            question_id, outcome, confidence, confirm_sum, deny_sum,
            independent_clusters, supporting_evidence_ids_json,
            policy_version, model, details, resolved_at
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public ResolutionRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Inserts or replaces the resolution for {@code resolution.questionId()}.
     * Upsert semantics allow manual review of {@code DISPUTED} questions to overwrite the prior row.
     */
    @Transactional
    public void save(Resolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        int updated = bindAll(jdbcClient.sql("""
                UPDATE resolutions
                SET outcome = :outcome,
                    confidence = :confidence,
                    confirm_sum = :confirmSum,
                    deny_sum = :denySum,
                    independent_clusters = :independentClusters,
                    supporting_evidence_ids_json = :supportingEvidenceIdsJson,
                    policy_version = :policyVersion,
                    model = :model,
                    details = :details,
                    resolved_at = :resolvedAt
                WHERE question_id = :questionId
                """), resolution).update();

        if (updated == 0) {
            bindAll(jdbcClient.sql("""
                    INSERT INTO resolutions (
                        question_id, outcome, confidence, confirm_sum, deny_sum,
                        independent_clusters, supporting_evidence_ids_json,
                        policy_version, model, details, resolved_at
                    ) VALUES (
                        :questionId, :outcome, :confidence, :confirmSum, :denySum,
                        :independentClusters, :supportingEvidenceIdsJson,
                        :policyVersion, :model, :details, :resolvedAt
                    )
                    """), resolution).update();
        }
    }

    public Optional<Resolution> findByQuestionId(UUID questionId) {
        Objects.requireNonNull(questionId, "questionId");
        return jdbcClient.sql("SELECT " + SELECT_COLUMNS + " FROM resolutions WHERE question_id = :questionId")
                .param("questionId", questionId.toString(), Types.VARCHAR)
                .query(this::mapRow)
                .optional();
    }

    private JdbcClient.StatementSpec bindAll(JdbcClient.StatementSpec spec, Resolution resolution) {
        return spec
                .param("questionId", resolution.questionId().toString(), Types.VARCHAR)
                .param("outcome", resolution.outcome().name(), Types.VARCHAR)
                .param("confidence", resolution.confidence(), Types.DOUBLE)
                .param("confirmSum", resolution.confirmSum(), Types.DOUBLE)
                .param("denySum", resolution.denySum(), Types.DOUBLE)
                .param("independentClusters", resolution.independentClusters(), Types.INTEGER)
                .param("supportingEvidenceIdsJson", JdbcSupport.writeJsonList(
                        objectMapper, resolution.supportingEvidenceIds(), "supportingEvidenceIds"), Types.VARCHAR)
                .param("policyVersion", resolution.policyVersion(), Types.INTEGER)
                .param("model", resolution.model(), Types.VARCHAR)
                .param("details", resolution.details(), Types.VARCHAR)
                .param("resolvedAt", JdbcSupport.toTimestamp(resolution.resolvedAt()), Types.TIMESTAMP);
    }

    private Resolution mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Resolution(
                UUID.fromString(rs.getString("question_id")),
                QuestionStatus.valueOf(rs.getString("outcome")),
                rs.getDouble("confidence"),
                rs.getDouble("confirm_sum"),
                rs.getDouble("deny_sum"),
                rs.getInt("independent_clusters"),
                JdbcSupport.readJsonList(
                        objectMapper,
                        rs.getString("supporting_evidence_ids_json"),
                        UUID_LIST_TYPE,
                        "supporting_evidence_ids_json"),
                rs.getInt("policy_version"),
                rs.getString("model"),
                rs.getString("details"),
                JdbcSupport.toInstant(rs.getTimestamp("resolved_at"))
        );
    }
}
