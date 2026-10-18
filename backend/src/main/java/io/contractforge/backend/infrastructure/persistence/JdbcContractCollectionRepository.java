package io.contractforge.backend.infrastructure.persistence;

import io.contractforge.backend.application.contract.ContractCollectionEntry;
import io.contractforge.backend.application.port.RepositoryPorts.ContractCollectionRepository;
import io.contractforge.backend.domain.model.ContractPartyRole;
import io.contractforge.backend.domain.model.ContractPricingModel;
import io.contractforge.backend.domain.model.ContractStatus;
import io.contractforge.backend.domain.model.ContractType;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.UploadStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** One bounded owner-scoped projection across upload, document, and confirmed contract state. */
@Repository
@Profile("!test")
public class JdbcContractCollectionRepository implements ContractCollectionRepository {
    private static final String SELECT = "WITH owned_collection AS ("
            + "SELECT u.id, u.user_id, COALESCE(d.contract_type, u.contract_type) AS contract_type, "
            + "COALESCE(u.created_at, d.created_at) AS created_at, "
            + "COALESCE(u.original_filename, d.filename) AS source_filename, "
            + "CASE WHEN d.id IS NULL AND u.status = ? AND u.expires_at <= ? THEN ? "
            + "WHEN d.id IS NULL THEN u.status ELSE NULL END AS upload_status, d.status AS document_status "
            + "FROM uploads u LEFT JOIN documents d ON u.id = d.id AND u.user_id = d.user_id "
            + "WHERE u.user_id = ? AND (u.status <> ? OR d.id IS NOT NULL) "
            + "UNION ALL "
            + "SELECT d.id, d.user_id, d.contract_type, d.created_at, d.filename, NULL::VARCHAR AS upload_status, "
            + "d.status AS document_status FROM documents d WHERE d.user_id = ? "
            + "AND NOT EXISTS (SELECT 1 FROM uploads u WHERE u.id = d.id AND u.user_id = d.user_id)) "
            + "SELECT owned.id AS item_id, owned.user_id, owned.contract_type, owned.created_at AS item_created_at, "
            + "owned.source_filename, owned.upload_status, owned.document_status, c.status AS contract_status, "
            + "CASE WHEN c.status IN (?, ?) THEN provider.name END AS provider, "
            + "CASE WHEN c.status IN (?, ?) THEN terms.pricing_model END AS pricing_model, "
            + "CASE WHEN c.status IN (?, ?) THEN terms.end_date END AS end_date, "
            + "EXISTS (SELECT 1 FROM recommendations r WHERE r.user_id = owned.user_id "
            + "AND r.document_id = owned.id AND (r.expires_at IS NULL OR r.expires_at > ?)) AS needs_attention "
            + "FROM owned_collection owned LEFT JOIN contracts c ON c.document_id = owned.id "
            + "LEFT JOIN contract_terms terms ON terms.contract_id = c.document_id "
            + "LEFT JOIN LATERAL (SELECT party.name FROM contract_parties party "
            + "WHERE party.contract_id = c.document_id AND party.role = ? ORDER BY party.id LIMIT 1) provider ON TRUE "
            + "ORDER BY owned.created_at DESC, owned.id DESC";

    private final JdbcTemplate jdbc;

    public JdbcContractCollectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ContractCollectionEntry> findByUserId(UUID userId, java.time.OffsetDateTime evaluatedAt) {
        return jdbc.query(SELECT, (row, number) -> new ContractCollectionEntry(
                        row.getObject("item_id", UUID.class),
                        ContractType.fromValue(row.getString("contract_type")),
                        row.getObject("item_created_at", java.time.OffsetDateTime.class),
                        row.getString("source_filename"),
                        nullableEnum(row.getString("upload_status"), UploadStatus.class),
                        nullableEnum(row.getString("document_status"), DocumentStatus.class),
                        nullableEnum(row.getString("contract_status"), ContractStatus.class),
                        row.getString("provider"),
                        nullableEnum(row.getString("pricing_model"), ContractPricingModel.class),
                        row.getObject("end_date", java.time.LocalDate.class), row.getBoolean("needs_attention")),
                UploadStatus.UPLOADING.value(), evaluatedAt, UploadStatus.EXPIRED.value(), userId,
                UploadStatus.COMPLETED.value(), userId,
                ContractStatus.ACTIVE.value(), ContractStatus.ARCHIVED.value(),
                ContractStatus.ACTIVE.value(), ContractStatus.ARCHIVED.value(),
                ContractStatus.ACTIVE.value(), ContractStatus.ARCHIVED.value(), evaluatedAt,
                ContractPartyRole.PROVIDER.value());
    }

    private static <T extends Enum<T>> T nullableEnum(String value, Class<T> type) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
