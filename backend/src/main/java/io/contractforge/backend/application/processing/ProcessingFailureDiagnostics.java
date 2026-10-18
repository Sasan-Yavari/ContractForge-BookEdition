package io.contractforge.backend.application.processing;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.contractforge.backend.application.extraction.ElectricityContractCandidateValidator;
import java.time.DateTimeException;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Emits fixed, payload-free classifications for processing failures. */
public final class ProcessingFailureDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessingFailureDiagnostics.class);

    private ProcessingFailureDiagnostics() { }

    public static void record(Stage stage, Throwable failure) {
        record(stage, exceptionCategory(failure));
    }

    public static void record(Stage stage, ExceptionCategory exceptionCategory) {
        LOG.atWarn()
                .addKeyValue("event_type", "processing_failure_diagnostic")
                .addKeyValue("diagnostic_stage", stage.value())
                .addKeyValue("exception_category", exceptionCategory.value())
                .log("processing_failure_diagnostic");
    }

    public static void recordSchemaValidationFailure(SchemaViolation violation, SchemaLocation location) {
        LOG.atWarn()
                .addKeyValue("event_type", "processing_failure_diagnostic")
                .addKeyValue("diagnostic_stage", Stage.LOCAL_SCHEMA_VALIDATION.value())
                .addKeyValue("exception_category", ExceptionCategory.ILLEGAL_ARGUMENT.value())
                .addKeyValue("schema_violation", violation.value())
                .addKeyValue("schema_location", location.value())
                .log("processing_failure_diagnostic");
    }

    public static void recordSchemaTypeMismatch(SchemaLocation location, ExpectedJsonType expectedType,
                                                ActualJsonType actualType) {
        LOG.atWarn()
                .addKeyValue("event_type", "processing_failure_diagnostic")
                .addKeyValue("diagnostic_stage", Stage.LOCAL_SCHEMA_VALIDATION.value())
                .addKeyValue("exception_category", ExceptionCategory.ILLEGAL_ARGUMENT.value())
                .addKeyValue("schema_violation", SchemaViolation.TYPE_MISMATCH.value())
                .addKeyValue("schema_location", location.value())
                .addKeyValue("schema_expected_type", expectedType.value())
                .addKeyValue("schema_actual_type", actualType.value())
                .log("processing_failure_diagnostic");
    }

    private static ExceptionCategory exceptionCategory(Throwable failure) {
        if (failure instanceof JsonProcessingException) return ExceptionCategory.JSON_PROCESSING;
        if (failure instanceof ElectricityContractCandidateValidator.InvalidCandidateException) {
            return ExceptionCategory.INVALID_CANDIDATE;
        }
        if (failure instanceof DateTimeException) return ExceptionCategory.DATE_TIME;
        if (failure instanceof IllegalArgumentException) return ExceptionCategory.ILLEGAL_ARGUMENT;
        if (failure instanceof NullPointerException) return ExceptionCategory.NULL_POINTER;
        if (failure instanceof NoSuchElementException) return ExceptionCategory.MISSING_VALUE;
        return ExceptionCategory.OTHER_RUNTIME;
    }

    public enum Stage {
        BEDROCK_REQUEST_CONSTRUCTION("bedrock_request_construction"),
        BEDROCK_CONVERSE("bedrock_converse"),
        BEDROCK_RESPONSE_EXTRACTION("bedrock_response_extraction"),
        JSON_PARSING("json_parsing"),
        LOCAL_SCHEMA_VALIDATION("local_schema_validation"),
        CANDIDATE_MAPPING("candidate_mapping"),
        BUSINESS_VALIDATION("business_validation"),
        AGGREGATE_CONSTRUCTION("aggregate_construction"),
        OTHER_RUNTIME("other_runtime");

        private final String value;

        Stage(String value) { this.value = value; }

        public String value() { return value; }
    }

    public enum ExceptionCategory {
        BEDROCK_SERVICE("bedrock_service"),
        JSON_PROCESSING("json_processing"),
        INVALID_CANDIDATE("invalid_candidate"),
        DATE_TIME("date_time"),
        ILLEGAL_ARGUMENT("illegal_argument"),
        NULL_POINTER("null_pointer"),
        MISSING_VALUE("missing_value"),
        OTHER_RUNTIME("other_runtime");

        private final String value;

        ExceptionCategory(String value) { this.value = value; }

        public String value() { return value; }
    }

    public enum SchemaViolation {
        REQUIRED_MISSING("required_missing"),
        ADDITIONAL_PROPERTY("additional_property"),
        TYPE_MISMATCH("type_mismatch"),
        ENUM_MISMATCH("enum_mismatch"),
        CONST_MISMATCH("const_mismatch");

        private final String value;

        SchemaViolation(String value) { this.value = value; }

        public String value() { return value; }
    }

    public enum SchemaLocation {
        ROOT("root"),
        SCHEMA_VERSION("schema_version"),
        PARTIES("parties"),
        PARTIES_ITEM("parties_item"),
        PARTY_ROLE("party_role"),
        PARTY_NAME("party_name"),
        START_DATE("start_date"),
        END_DATE("end_date"),
        DURATION_MONTHS("duration_months"),
        RENEWAL_TYPE("renewal_type"),
        PRICING_MODEL("pricing_model"),
        ELECTRICITY_DATA("electricity_data"),
        CONSUMPTION("consumption"),
        CONSUMPTION_ANNUAL_KWH("consumption_annual_kwh"),
        PRICING("pricing"),
        PRICING_RATE("pricing_rate"),
        FIXED_CHARGE("fixed_charge"),
        RETURN_DELIVERY("return_delivery"),
        DISCOUNT("discount"),
        UNKNOWN("unknown");

        private final String value;

        SchemaLocation(String value) { this.value = value; }

        public String value() { return value; }
    }

    public enum ExpectedJsonType {
        OBJECT("object"),
        ARRAY("array"),
        STRING("string"),
        INTEGER("integer"),
        NUMBER("number"),
        BOOLEAN("boolean"),
        NULL("null"),
        STRING_OR_NULL("string_or_null"),
        INTEGER_OR_NULL("integer_or_null"),
        NUMBER_STRING_OR_NULL("number_string_or_null"),
        UNKNOWN("unknown");

        private final String value;

        ExpectedJsonType(String value) { this.value = value; }

        public String value() { return value; }
    }

    public enum ActualJsonType {
        OBJECT("object"),
        ARRAY("array"),
        STRING("string"),
        NUMBER("number"),
        BOOLEAN("boolean"),
        NULL("null"),
        UNKNOWN("unknown");

        private final String value;

        ActualJsonType(String value) { this.value = value; }

        public String value() { return value; }
    }
}
