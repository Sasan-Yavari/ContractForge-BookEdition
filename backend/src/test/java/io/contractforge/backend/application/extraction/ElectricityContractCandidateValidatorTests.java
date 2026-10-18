package io.contractforge.backend.application.extraction;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElectricityContractCandidateValidatorTests {
    private final ElectricityContractCandidateValidator validator =
            new ElectricityContractCandidateValidator(new ObjectMapper());

    @Test
    void acceptsApprovedElectricityCandidate() {
        assertThatCode(() -> validator.validate(candidate("{\"consumption\":{\"annual_kwh\":3200},\"pricing\":{\"model\":\"FIXED\"}}")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWrongSchemaVersion() {
        assertThatThrownBy(() -> validator.validate(new ContractExtractionPort.Candidate("2", List.of(), null,
                null, null, null, null, new JsonValue("{}"))))
                .isInstanceOf(ElectricityContractCandidateValidator.InvalidCandidateException.class);
    }

    @Test
    void rejectsInvalidBusinessDatesAndValues() {
        ContractExtractionPort.Candidate invalid = new ContractExtractionPort.Candidate(ContractExtractionPort.CURRENT_SCHEMA_VERSION, List.of(),
                java.time.LocalDate.of(2027, 1, 1), java.time.LocalDate.of(2026, 1, 1), 0,
                "UNKNOWN", "UNKNOWN", new JsonValue("{}"));

        assertThatThrownBy(() -> validator.validate(invalid))
                .isInstanceOf(ElectricityContractCandidateValidator.InvalidCandidateException.class);
    }

    @Test
    void rejectsIrrelevantElectricityDataInsteadOfPersistingIt() {
        assertThatThrownBy(() -> validator.validate(candidate("{\"consumption\":{\"annual_kwh\":3200,\"secret\":\"ignore\"}}")))
                .isInstanceOf(ElectricityContractCandidateValidator.InvalidCandidateException.class);
    }

    @Test
    void rejectsMalformedElectricityJson() {
        assertThatThrownBy(() -> validator.validate(candidate("not-json")))
                .isInstanceOf(ElectricityContractCandidateValidator.InvalidCandidateException.class);
    }

    @Test
    void rejectsMissingPartiesAndInvalidNestedValues() {
        assertThatThrownBy(() -> validator.validate(new ContractExtractionPort.Candidate(ContractExtractionPort.CURRENT_SCHEMA_VERSION, List.of(), null,
                null, null, null, null, new JsonValue("{}"))))
                .isInstanceOf(ElectricityContractCandidateValidator.InvalidCandidateException.class);
        assertThatThrownBy(() -> validator.validate(candidate("{\"consumption\":{\"annual_kwh\":\"unknown\"}}")))
                .isInstanceOf(ElectricityContractCandidateValidator.InvalidCandidateException.class);
        assertThatThrownBy(() -> validator.validate(candidate("{\"pricing\":{\"rates\":[{\"value\":\"unknown\"}]}}")))
                .isInstanceOf(ElectricityContractCandidateValidator.InvalidCandidateException.class);
        assertThatThrownBy(() -> validator.validate(candidate("{\"pricing\":{\"rates\":[{\"value\":1,\"unit\":{}}]}}")))
                .isInstanceOf(ElectricityContractCandidateValidator.InvalidCandidateException.class);
        assertThatThrownBy(() -> validator.validate(candidate("{\"fixed_charges\":[{\"type\":\"OTHER\",\"amount\":1}]}")))
                .isInstanceOf(ElectricityContractCandidateValidator.InvalidCandidateException.class);
    }

    private ContractExtractionPort.Candidate candidate(String electricityData) {
        return new ContractExtractionPort.Candidate(ContractExtractionPort.CURRENT_SCHEMA_VERSION, List.of(
                new ContractExtractionPort.Party("CUSTOMER", "Customer"),
                new ContractExtractionPort.Party("PROVIDER", "Provider")), null, null, null,
                "FIXED_TERM", "FIXED", new JsonValue(electricityData));
    }
}
