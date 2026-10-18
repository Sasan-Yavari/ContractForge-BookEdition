package io.contractforge.backend.application.extraction;

import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import java.time.LocalDate;
import java.util.List;

/** Provider-neutral boundary for strict, electricity-only contract candidates. */
public interface ContractExtractionPort {
    String CURRENT_SCHEMA_VERSION = "1";

    ExtractionResult extract(String documentText, InvocationRecorder invocationRecorder);

    @FunctionalInterface
    interface InvocationRecorder {
        void recordInvocation();
    }

    record Candidate(String schemaVersion, List<Party> parties, LocalDate startDate, LocalDate endDate,
                     Integer durationMonths, String renewalType, String pricingModel, JsonValue electricityData) {
        public Candidate {
            parties = parties == null ? List.of() : List.copyOf(parties);
        }
    }

    record Party(String role, String name) { }

    record ExtractionResult(Candidate candidate, JsonValue usageData) {
        public ExtractionResult {
            if (candidate == null || usageData == null) throw new IllegalArgumentException("Invalid extraction result");
        }
    }

    class RetryableExtractionException extends RuntimeException {
        public RetryableExtractionException(Throwable cause) { super(cause); }
    }

    class PermanentExtractionException extends RuntimeException {
        public PermanentExtractionException(Throwable cause) { super(cause); }
    }
}
