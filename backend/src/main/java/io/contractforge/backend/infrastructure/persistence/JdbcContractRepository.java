package io.contractforge.backend.infrastructure.persistence;

import io.contractforge.backend.application.port.RepositoryPorts.ContractRepository;
import io.contractforge.backend.domain.model.PersistenceModels.Contract;
import io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate;
import io.contractforge.backend.domain.model.PersistenceModels.ContractParty;
import io.contractforge.backend.domain.model.PersistenceModels.ContractTerms;
import io.contractforge.backend.domain.model.PersistenceModels.MonitoringContract;
import io.contractforge.backend.domain.model.ContractStatus;
import io.contractforge.backend.domain.model.ContractType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcContractRepository implements ContractRepository {

    private final JdbcTemplate jdbc;

    public JdbcContractRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ContractAggregate> findByDocumentId(UUID documentId) {
        List<ContractAggregate> contracts = jdbc.query("SELECT document_id, type, status, data, version, created_at, updated_at, archived_at " +
                "FROM contracts WHERE document_id = ?", (row, number) -> {
            Contract contract = new Contract(row.getObject("document_id", UUID.class), row.getString("type"),
                    row.getString("status"), JdbcJsonb.read(row.getObject("data")), row.getLong("version"),
                    row.getObject("created_at", java.time.OffsetDateTime.class),
                    row.getObject("updated_at", java.time.OffsetDateTime.class),
                    row.getObject("archived_at", java.time.OffsetDateTime.class));
            List<ContractParty> parties = jdbc.query("SELECT id, contract_id, role, name FROM contract_parties "
                    + "WHERE contract_id = ? ORDER BY id", this::mapParty, documentId);
            List<ContractTerms> terms = jdbc.query("SELECT contract_id, start_date, end_date, contract_duration, "
                    + "renewal_type, pricing_model, electricity_data FROM contract_terms WHERE contract_id = ?",
                    this::mapTerms, documentId);
            return new ContractAggregate(contract, parties, terms.stream().findFirst().orElse(null));
        }, documentId);
        return contracts.stream().findFirst();
    }

    @Override
    public List<MonitoringContract> findEligibleForMonitoring() {
        return jdbc.query("SELECT c.document_id, d.user_id, c.type, c.status, c.data, c.version, "
                        + "c.created_at, c.updated_at, c.archived_at, p.id AS party_id, p.role AS party_role, "
                        + "p.name AS party_name, t.contract_id AS terms_contract_id, t.start_date, t.end_date, "
                        + "t.contract_duration, t.renewal_type, t.pricing_model, t.electricity_data "
                        + "FROM contracts c JOIN documents d ON d.id = c.document_id "
                        + "JOIN users u ON u.id = d.user_id AND u.status = 'ACTIVE' "
                        + "LEFT JOIN contract_parties p ON p.contract_id = c.document_id "
                        + "LEFT JOIN contract_terms t ON t.contract_id = c.document_id "
                        + "WHERE c.type = ? AND c.status = ? "
                        + "ORDER BY c.document_id, p.id",
                row -> {
                    Map<UUID, MonitoringBuilder> byDocument = new LinkedHashMap<>();
                    while (row.next()) {
                        UUID documentId = row.getObject("document_id", UUID.class);
                        MonitoringBuilder builder = byDocument.get(documentId);
                        if (builder == null) {
                            Contract contract = new Contract(documentId, row.getString("type"), row.getString("status"),
                                    JdbcJsonb.read(row.getObject("data")), row.getLong("version"),
                                    row.getObject("created_at", java.time.OffsetDateTime.class),
                                    row.getObject("updated_at", java.time.OffsetDateTime.class),
                                    row.getObject("archived_at", java.time.OffsetDateTime.class));
                            ContractTerms terms = row.getObject("terms_contract_id") == null ? null
                                    : new ContractTerms(row.getObject("terms_contract_id", UUID.class),
                                    row.getObject("start_date", java.time.LocalDate.class),
                                    row.getObject("end_date", java.time.LocalDate.class),
                                    (Integer) row.getObject("contract_duration"), row.getString("renewal_type"),
                                    row.getString("pricing_model"), row.getObject("electricity_data") == null
                                    ? null : JdbcJsonb.read(row.getObject("electricity_data")));
                            builder = new MonitoringBuilder(row.getObject("user_id", UUID.class), contract, terms);
                            byDocument.put(documentId, builder);
                        }
                        UUID partyId = row.getObject("party_id", UUID.class);
                        if (partyId != null) {
                            builder.parties.add(new ContractParty(partyId, documentId,
                                    row.getString("party_role"), row.getString("party_name")));
                        }
                    }
                    return byDocument.values().stream().map(MonitoringBuilder::toMonitoringContract).toList();
                }, ContractType.ELECTRICITY.value(), ContractStatus.ACTIVE.value());
    }

    @Override
    public void save(ContractAggregate aggregate) {
        Contract contract = aggregate.contract();
        jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO contracts "
                        + "(document_id, type, status, data, version, created_at, updated_at, archived_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
                statement.setObject(1, contract.documentId());
                statement.setString(2, contract.type().value());
                statement.setString(3, contract.status().value());
                statement.setObject(4, JdbcJsonb.value(contract.data()));
                statement.setLong(5, contract.version());
                statement.setObject(6, contract.createdAt());
                statement.setObject(7, contract.updatedAt());
                statement.setObject(8, contract.archivedAt());
                return statement;
            });
        for (ContractParty party : aggregate.parties()) {
                jdbc.update("INSERT INTO contract_parties (id, contract_id, role, name) VALUES (?, ?, ?, ?)",
                        party.id(), party.contractId(), party.role().value(), party.name());
        }
        if (aggregate.terms() != null) {
                ContractTerms terms = aggregate.terms();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("INSERT INTO contract_terms "
                            + "(contract_id, start_date, end_date, contract_duration, renewal_type, pricing_model, electricity_data) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)");
                    statement.setObject(1, terms.contractId());
                    statement.setObject(2, terms.startDate());
                    statement.setObject(3, terms.endDate());
                    statement.setObject(4, terms.contractDuration());
                    statement.setString(5, terms.renewalType() == null ? null : terms.renewalType().value());
                    statement.setString(6, terms.pricingModel() == null ? null : terms.pricingModel().value());
                    statement.setObject(7, terms.electricityData() == null ? null : JdbcJsonb.value(terms.electricityData()));
                    return statement;
                });
        }
    }

    @Override
    public int updateDraft(ContractAggregate aggregate, long expectedVersion) {
        return update(aggregate, expectedVersion, ContractStatus.DRAFT);
    }

    @Override
    public int updateActive(ContractAggregate aggregate, long expectedVersion) {
        return update(aggregate, expectedVersion, ContractStatus.ACTIVE);
    }

    private int update(ContractAggregate aggregate, long expectedVersion, ContractStatus requiredStatus) {
        Contract contract = aggregate.contract();
        int updated = jdbc.update("UPDATE contracts SET data = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1 "
                + "WHERE document_id = ? AND status = ? AND version = ?",
                JdbcJsonb.value(contract.data()), contract.documentId(), requiredStatus.value(), expectedVersion);
        if (updated != 1) return 0;
        jdbc.update("DELETE FROM contract_parties WHERE contract_id = ?", contract.documentId());
        for (ContractParty party : aggregate.parties()) {
            jdbc.update("INSERT INTO contract_parties (id, contract_id, role, name) VALUES (?, ?, ?, ?)",
                    party.id(), party.contractId(), party.role().value(), party.name());
        }
        jdbc.update("DELETE FROM contract_terms WHERE contract_id = ?", contract.documentId());
        if (aggregate.terms() != null) {
            ContractTerms terms = aggregate.terms();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO contract_terms "
                        + "(contract_id, start_date, end_date, contract_duration, renewal_type, pricing_model, electricity_data) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)");
                statement.setObject(1, terms.contractId()); statement.setObject(2, terms.startDate());
                statement.setObject(3, terms.endDate()); statement.setObject(4, terms.contractDuration());
                statement.setString(5, terms.renewalType() == null ? null : terms.renewalType().value());
                statement.setString(6, terms.pricingModel() == null ? null : terms.pricingModel().value());
                statement.setObject(7, terms.electricityData() == null ? null : JdbcJsonb.value(terms.electricityData()));
                return statement;
            });
        }
        return 1;
    }

    @Override
    public int approveDraft(UUID documentId, long expectedVersion) {
        return jdbc.update("UPDATE contracts SET status = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1 "
                + "WHERE document_id = ? AND status = ? AND version = ?", ContractStatus.ACTIVE.value(), documentId,
                ContractStatus.DRAFT.value(), expectedVersion);
    }

    private ContractParty mapParty(java.sql.ResultSet row, int rowNumber) throws java.sql.SQLException {
        return new ContractParty(row.getObject("id", UUID.class), row.getObject("contract_id", UUID.class),
                row.getString("role"), row.getString("name"));
    }

    private ContractTerms mapTerms(java.sql.ResultSet row, int rowNumber) throws java.sql.SQLException {
        return new ContractTerms(row.getObject("contract_id", UUID.class), row.getObject("start_date", java.time.LocalDate.class),
                row.getObject("end_date", java.time.LocalDate.class), (Integer) row.getObject("contract_duration"),
                row.getString("renewal_type"), row.getString("pricing_model"),
                row.getObject("electricity_data") == null ? null : JdbcJsonb.read(row.getObject("electricity_data")));
    }

    private static final class MonitoringBuilder {
        private final UUID userId;
        private final Contract contract;
        private final ContractTerms terms;
        private final List<ContractParty> parties = new ArrayList<>();

        private MonitoringBuilder(UUID userId, Contract contract, ContractTerms terms) {
            this.userId = userId;
            this.contract = contract;
            this.terms = terms;
        }

        private MonitoringContract toMonitoringContract() {
            return new MonitoringContract(userId, new ContractAggregate(contract, parties, terms));
        }
    }
}
