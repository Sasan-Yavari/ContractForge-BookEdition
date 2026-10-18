package io.contractforge.backend.infrastructure.persistence;

import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.domain.model.PersistenceModels.Document;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.ContractType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcDocumentRepository implements DocumentRepository {

    private static final String COLUMNS = "id, user_id, storage_key, filename, mime_type, size, contract_type, status, created_at, updated_at";
    private final JdbcTemplate jdbc;

    public JdbcDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Document> findById(UUID id) {
        List<Document> documents = jdbc.query("SELECT " + COLUMNS + " FROM documents WHERE id = ?", this::map, id);
        return documents.stream().findFirst();
    }

    @Override
    public Optional<Document> findByIdForUpdate(UUID id) {
        List<Document> documents = jdbc.query("SELECT " + COLUMNS + " FROM documents WHERE id = ? FOR UPDATE", this::map, id);
        return documents.stream().findFirst();
    }

    @Override
    public void save(Document document) {
        jdbc.update("INSERT INTO documents (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                document.id(), document.userId(), document.storageKey(), document.filename(), document.mimeType(),
                document.size(), document.contractType().value(), document.status().value(), document.createdAt(), document.updatedAt());
    }

    @Override
    public void markProcessingSucceeded(UUID id) {
        int updated = jdbc.update("UPDATE documents SET status = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND status = ?", DocumentStatus.SUCCESS.value(), id, DocumentStatus.PROCESSING.value());
        if (updated != 1) {
            throw new IllegalStateException("Expected one processing document: " + id);
        }
    }

    @Override
    public int markProcessingFailed(UUID id) {
        return jdbc.update("UPDATE documents SET status = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND status = ?", DocumentStatus.FAILED.value(), id, DocumentStatus.PROCESSING.value());
    }

    @Override
    public void deleteById(UUID id) {
        jdbc.update("DELETE FROM documents WHERE id = ?", id);
    }

    @Override
    public Optional<String> deleteOwnedById(UUID id, UUID userId) {
        List<String> keys = jdbc.query("DELETE FROM documents WHERE id = ? AND user_id = ? RETURNING storage_key",
                (row, number) -> row.getString("storage_key"), id, userId);
        return keys.stream().findFirst();
    }

    private Document map(java.sql.ResultSet row, int rowNumber) throws java.sql.SQLException {
        return new Document(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                row.getString("storage_key"), row.getString("filename"), row.getString("mime_type"),
                row.getLong("size"), ContractType.fromValue(row.getString("contract_type")),
                DocumentStatus.fromValue(row.getString("status")), row.getObject("created_at", java.time.OffsetDateTime.class),
                row.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
