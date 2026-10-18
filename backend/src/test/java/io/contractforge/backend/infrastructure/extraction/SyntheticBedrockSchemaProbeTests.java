package io.contractforge.backend.infrastructure.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.accounting.AccountingPort;
import io.contractforge.backend.application.accounting.CostCalculation;
import io.contractforge.backend.application.extraction.ContractExtractionAccountingProperties;
import io.contractforge.backend.application.extraction.ContractExtractionPort;
import io.contractforge.backend.application.extraction.DirectTextExtractorPort;
import io.contractforge.backend.application.extraction.DocumentTextExtractionService;
import io.contractforge.backend.application.extraction.ElectricityContractCandidateValidator;
import io.contractforge.backend.application.extraction.ModelInputPreparationService;
import io.contractforge.backend.application.extraction.TextExtractionProperties;
import io.contractforge.backend.application.processing.ElectricityContractProcessingWorkService;
import io.contractforge.backend.application.processing.PermanentProcessingException;
import io.contractforge.backend.application.processing.ProcessingFailureDiagnostics;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.PersistenceModels.Document;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

/**
 * Manual, opt-in reproduction through the production extraction path.
 * All document fixtures are fictional. Provider text is held in memory only; output is restricted to
 * schema-derived paths, bounded rule/type names and provider usage metadata.
 */
class SyntheticBedrockSchemaProbeTests {
    private static final UUID SYNTHETIC_DOCUMENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000216");
    private static final UUID SYNTHETIC_USER_ID = UUID.fromString("00000000-0000-4000-8000-000000000217");
    private static final String SYNTHETIC_DOCUMENT = """
            SYNTHETIC ELECTRICITY SUPPLY AGREEMENT FOR TESTING ONLY
            Customer: Synthetic Buyer AB
            Provider: Example Energy AB
            Supply start date: 2026-01-01.
            The contract term is twelve months and the agreement is fixed term.
            Electricity is priced at a flat EUR 0.155 per kWh for the full term.
            No annual consumption, separate time-of-use rate, index, fixed charge,
            return-delivery compensation, or discount is stated.
            """;
    private static final List<SyntheticPricingContract> SYNTHETIC_PRICING_CONTRACTS = List.of(
            new SyntheticPricingContract("flat_rate", SYNTHETIC_DOCUMENT, SYNTHETIC_DOCUMENT_ID),
            new SyntheticPricingContract("time_of_use", """
                    SYNTHETIC ELECTRICITY SUPPLY AGREEMENT FOR TESTING ONLY
                    Customer: Fictional Harbor Works
                    Provider: Example Current Cooperative
                    Supply starts on 2026-02-01 for a fixed twelve-month term.
                    Daytime electricity is EUR 0.219 per kWh and overnight electricity is EUR 0.132 per kWh.
                    The agreement names day and overnight periods but gives no clock boundaries, annual
                    consumption, fixed charge, return-delivery compensation, or discount.
                    """, UUID.fromString("00000000-0000-4000-8000-000000000218")),
            new SyntheticPricingContract("indexed", """
                    SYNTHETIC ELECTRICITY SUPPLY AGREEMENT FOR TESTING ONLY
                    Customer: Fictional North Pier Studio
                    Provider: Example Current Cooperative
                    Supply starts on 2026-03-01 and renews monthly.
                    The energy price follows the fictional North Sea Hourly Index plus a EUR 0.018 per kWh
                    supplier margin. The contract gives no annual consumption, separate time-of-use rates,
                    fixed charge, return-delivery compensation, or discount.
                    """, UUID.fromString("00000000-0000-4000-8000-000000000219")),
            new SyntheticPricingContract("tariff_only_dates_and_supply_charge", """
                    SYNTHETIC ELECTRICITY CONTRACT FOR TESTING ONLY
                    Customer: Fictional Lighthouse Workshop
                    Provider: Example Current Cooperative
                    The electricity contract has a variable pricing model.
                    A tariff price schedule is valid from 2024-01-01 through 2025-12-31.
                    No overall electricity contract start date, end date, or duration is stated.
                    The supplier charges a fixed standing supply charge of EUR 9.50 per month.
                    A separate network distribution charge of EUR 21.00 per month and electricity tax
                    of EUR 0.10 per kWh are listed separately.
                    """, UUID.fromString("00000000-0000-4000-8000-000000000221")));

    @Test
    @EnabledIfSystemProperty(named = "contractforge.syntheticBedrockProbe", matches = "true")
    void invokesProductionPathForEachSyntheticPricingContractAndReportsAllViolations() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode schema = loadSchema(json);
        BedrockProperties properties = SyntheticBedrockProbeSettings.fromEnvironment(System.getenv());
        try (DefaultCredentialsProvider credentialProvider = DefaultCredentialsProvider.create();
                BedrockRuntimeClient delegate = new BedrockConfiguration().bedrockRuntimeClient(properties)) {
            List<SyntheticPricingContract> fixtures = new ArrayList<>();
            String syntheticPdfPath = System.getProperty("contractforge.syntheticBedrockPdfPath");
            if (syntheticPdfPath == null || syntheticPdfPath.isBlank()) {
                fixtures.addAll(SYNTHETIC_PRICING_CONTRACTS);
            } else {
                byte[] pdfBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(syntheticPdfPath));
                var pdfText = new LocalPdfTextExtractorAdapter().extract(pdfBytes);
                String extractedText = pdfText.text();
                assertTrue(extractedText.stripLeading().startsWith("SYNTHETIC TEST DOCUMENT - NOT A REAL CONTRACT"),
                        "The PDF trace probe accepts only the approved synthetic fixture marker");
                System.out.println("synthetic_pdf_trace fixture=synthetic_216_pdf stage=pdfbox_text_extraction"
                        + " pages=" + pdfText.pages()
                        + " characters=" + extractedText.length()
                        + " has_start_phrase=" + extractedText.contains("Contract start date: 1 November 2026")
                        + " has_end_phrase=" + extractedText.contains("Contract end date: 31 October 2028")
                        + " has_duration_phrase=" + extractedText.contains("Contract duration: 24 months")
                        + " has_renewal_phrase=" + extractedText.contains("Term: fixed, with no automatic renewal.")
                        + " has_pricing_model_phrase=" + extractedText.contains("Pricing model: fixed.")
                        + " has_tariff_validity=" + extractedText.contains("TARIFF VALIDITY")
                        + " has_rate_1=" + extractedText.contains("EUR 0.12345/kWh")
                        + " has_rate_2=" + extractedText.contains("EUR 0.09876/kWh")
                        + " has_supply_charge=" + extractedText.contains("EUR 8.76 per month"));
                fixtures.add(new SyntheticPricingContract("synthetic_216_pdf", extractedText,
                        UUID.fromString("00000000-0000-4000-8000-000000000220")));
            }
            List<String> unsuccessfulFixtures = new ArrayList<>();
            for (SyntheticPricingContract fixture : fixtures) {
                if (!invokeAndReportFixture(fixture, json, schema, properties, credentialProvider, delegate)) {
                    unsuccessfulFixtures.add(fixture.label());
                }
            }
            assertTrue(unsuccessfulFixtures.isEmpty(),
                    "Synthetic processing was unsuccessful for fixture(s): "
                            + String.join(",", unsuccessfulFixtures));
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "contractforge.syntheticBedrockPdfPath", matches = ".+")
    void extractsApprovedSyntheticPdfWithProductionPdfBoxAdapter() throws Exception {
        String path = System.getProperty("contractforge.syntheticBedrockPdfPath");
        var extracted = new LocalPdfTextExtractorAdapter().extract(
                java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)));
        String text = extracted.text();
        assertTrue(text.stripLeading().startsWith("SYNTHETIC TEST DOCUMENT - NOT A REAL CONTRACT"));
        assertTrue(text.contains("Contract start date: 1 November 2026"));
        assertTrue(text.contains("Contract end date: 31 October 2028"));
        assertTrue(text.contains("Contract duration: 24 months"));
        assertTrue(text.contains("Term: fixed, with no automatic renewal."));
        assertTrue(text.contains("Pricing model: fixed."));
        assertTrue(text.contains("TARIFF VALIDITY"));
        assertTrue(text.contains("EUR 0.12345/kWh"));
        assertTrue(text.contains("EUR 0.09876/kWh"));
        assertTrue(text.contains("EUR 8.76 per month"));
        System.out.println("synthetic_pdf_trace fixture=synthetic_216_pdf stage=pdfbox_text_extraction"
                + " pages=" + extracted.pages()
                + " characters=" + text.length()
                + " contract_dates_duration_renewal_present=true"
                + " pricing_model_present=true"
                + " tariff_validity_present=true"
                + " both_rates_present=true"
                + " supply_charge_present=true");
    }

    private boolean invokeAndReportFixture(
            SyntheticPricingContract fixture,
            ObjectMapper json,
            JsonNode schema,
            BedrockProperties properties,
            DefaultCredentialsProvider credentialProvider,
            BedrockRuntimeClient delegate) throws Exception {
        AtomicReference<ConverseRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<ConverseResponse> capturedResponse = new AtomicReference<>();
        BedrockRuntimeClient observedClient = (BedrockRuntimeClient) Proxy.newProxyInstance(
                BedrockRuntimeClient.class.getClassLoader(), new Class<?>[] {BedrockRuntimeClient.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("converse")) {
                        throw new UnsupportedOperationException("Synthetic probe supports Converse only");
                    }
                    ConverseRequest request = (ConverseRequest) args[0];
                    capturedRequest.set(request);
                    preflightCredentials(credentialProvider::resolveCredentials);
                    ConverseResponse response = delegate.converse(request);
                    capturedResponse.set(response);
                    return response;
                });

        BedrockContractExtractionAdapter adapter = new BedrockContractExtractionAdapter(
                observedClient, json, properties);
        RecordingAccounting accounting = new RecordingAccounting();
        var workService = new ElectricityContractProcessingWorkService(
                new SyntheticTextExtraction(fixture.document()),
                new ModelInputPreparationService(new TextExtractionProperties(0, 0, 0, 0, 200_000)),
                adapter, new ElectricityContractCandidateValidator(json), accounting,
                new ContractExtractionAccountingProperties(properties.modelId(), "EUR"),
                (provider, service, dimension, usage, occurredAt) -> CostCalculation.unknown("EUR"), json);
        Document syntheticDocument = new Document(fixture.documentId(), SYNTHETIC_USER_ID,
                "synthetic-only", "synthetic-pricing-contract.pdf", "application/pdf", 1,
                DocumentStatus.PROCESSING, OffsetDateTime.now(), OffsetDateTime.now());

        RuntimeException processingFailure = null;
        boolean processingSucceeded = false;
        io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate mappedAggregate = null;
        try {
            mappedAggregate = workService.process(syntheticDocument);
            processingSucceeded = mappedAggregate != null;
        } catch (RuntimeException failure) {
            processingFailure = failure;
        }

        ConverseRequest request = capturedRequest.get();
        assertNotNull(request, "Production adapter did not construct a Converse request");
        assertEquals(properties.modelId(), request.modelId());
        assertEquals(properties.maxOutputTokens(), request.inferenceConfig().maxTokens());
        assertEquals(0.0f, request.inferenceConfig().temperature());
        assertTrue(request.system().getFirst().text().endsWith("JSON Schema:\n" + loadSchemaText()));
        assertEquals("<document_text>\n" + fixture.document() + "\n</document_text>",
                request.messages().getFirst().content().getFirst().text());

        for (AccountingPort.UsageEvent usageEvent : accounting.events) {
            assertEquals("LLM_EXTRACTION", usageEvent.operation());
            assertEquals("aws", usageEvent.provider());
            assertEquals(properties.modelId(), usageEvent.service());
            assertEquals(SYNTHETIC_USER_ID, usageEvent.userId());
            assertEquals(fixture.documentId(), usageEvent.documentId());
        }

        ConverseResponse response = capturedResponse.get();
        if (response == null) {
            ProbeProcessingReport report = processingReport(processingSucceeded, processingFailure,
                    false, List.of());
            System.out.println("synthetic_pricing_fixture=" + fixture.label()
                    + " diagnostic_completion=" + report.diagnosticCompletion().value()
                    + " processing_outcome=" + report.outcome().value()
                    + " failure_stage=" + report.failureStageValue()
                    + " failure_category=" + report.failureCategoryValue()
                    + " failure_kind=" + report.failureKindValue()
                    + " exception_classes=" + report.exceptionClasses()
                    + " aws_error_code=" + report.awsErrorCode()
                    + " stop_reason=unknown output_truncated=unknown json_shape=unavailable"
                    + " schema_violation_count=0 accounting_event_count=" + accounting.events.size());
            return false;
        }

        String providerText = response.output() == null || response.output().message() == null
                ? null
                : response.output().message().content().stream()
                        .map(block -> block.text()).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        JsonNode candidate = parseCandidate(json, providerText);
        CandidateExpectationReport expectedValueReport = switch (fixture.label()) {
            case "synthetic_216_pdf" -> synthetic216CandidateChecks(candidate);
            case "tariff_only_dates_and_supply_charge" -> tariffOnlyDateAndChargeChecks(candidate);
            default -> CandidateExpectationReport.empty();
        };
        if (fixture.label().equals("synthetic_216_pdf")) {
            traceCandidate("provider_candidate_before_normalization", fixture.label(), candidate);
        }
        String shape = candidate == null ? "unparseable"
                : candidate.isObject() ? "object" : actualShape(candidate);
        List<SyntheticSchemaViolationCollector.Violation> rawViolations = candidate == null
                ? List.of() : SyntheticSchemaViolationCollector.collect(candidate, schema);
        if (candidate != null) {
            BedrockContractExtractionAdapter.normalizeOptionalAnnualKwhNull(candidate);
            BedrockContractExtractionAdapter.normalizeOptionalPricingRateNulls(candidate);
        }
        if (fixture.label().equals("synthetic_216_pdf")) {
            traceCandidate("provider_candidate_after_approved_normalization", fixture.label(), candidate);
            traceMappedAggregate(fixture.label(), mappedAggregate, json);
        }
        List<SyntheticSchemaViolationCollector.Violation> violations = candidate == null
                ? List.of() : SyntheticSchemaViolationCollector.collect(candidate, schema);
        ProbeProcessingReport report = processingReport(processingSucceeded, processingFailure,
                true, violations);
        AccountingPort.UsageEvent usageEvent = accounting.events.size() == 1 ? accounting.events.getFirst() : null;

        System.out.println("synthetic_pricing_fixture=" + fixture.label()
                + " diagnostic_completion=" + report.diagnosticCompletion().value()
                + " processing_outcome=" + report.outcome().value()
                + " failure_stage=" + report.failureStageValue()
                + " failure_category=" + report.failureCategoryValue()
                + " failure_kind=" + report.failureKindValue()
                + " exception_classes=" + report.exceptionClasses()
                + " aws_error_code=" + report.awsErrorCode()
                + " stop_reason=" + stopReason(response.stopReason())
                + " output_truncated=" + outputTruncated(response.stopReason())
                + " json_shape=" + shape
                + " pre_normalization_schema_violation_count=" + rawViolations.size()
                + " schema_violation_count=" + violations.size()
                + " expected_value_mismatch_count=" + expectedValueReport.mismatchCount()
                + " expected_value_not_evaluated_count=" + expectedValueReport.notEvaluatedCount()
                + " expected_value_unavailable_count=" + expectedValueReport.unavailableCount()
                + " accounting_event_count=" + accounting.events.size()
                + " cost_status=" + (usageEvent == null ? "unknown" : usageEvent.costStatus().name())
                + " input_tokens=" + tokenCount(response.usage() == null
                        ? null : response.usage().inputTokens())
                + " output_tokens=" + tokenCount(response.usage() == null
                        ? null : response.usage().outputTokens()));
        for (SyntheticSchemaViolationCollector.Violation violation : rawViolations) {
            System.out.println("synthetic_pricing_pre_normalization_violation fixture=" + fixture.label()
                    + " violation=" + violation.safeSummary());
        }
        for (SyntheticSchemaViolationCollector.Violation violation : violations) {
            System.out.println("synthetic_pricing_violation fixture=" + fixture.label()
                    + " violation=" + violation.safeSummary());
        }
        for (ExpectedValueCheck check : expectedValueReport.checks()) {
            System.out.println("synthetic_pdf_expected_value_check fixture=" + fixture.label()
                    + " field=" + check.field() + " status=" + check.status().value());
        }
        return report.outcome() == ProcessingOutcome.SUCCEEDED
                && accounting.events.size() == 1 && violations.isEmpty() && expectedValueReport.allMatched();
    }

    private static CandidateExpectationReport synthetic216CandidateChecks(JsonNode candidate) {
        List<ExpectedValueCheck> checks = new ArrayList<>();
        if (candidate == null) {
            return new CandidateExpectationReport(List.of(
                    new ExpectedValueCheck("candidate", ExpectedValueStatus.UNAVAILABLE)));
        }
        if (!candidate.isObject()) {
            return new CandidateExpectationReport(List.of(
                    new ExpectedValueCheck("candidate", ExpectedValueStatus.MISMATCH)));
        }
        JsonNode parties = candidate.path("parties");
        JsonNode provider = parties.isArray() && parties.size() == 2
                ? arrayObjectWithText(parties, "role", "PROVIDER") : null;
        JsonNode customer = parties.isArray() && parties.size() == 2
                ? arrayObjectWithText(parties, "role", "CUSTOMER") : null;
        checks.add(check("parties", parties.isArray() && parties.size() == 2));
        checks.add(check("parties_provider_role", provider != null));
        addTextCheck(checks, provider,
                "name", "Fictional Test Energy B.V.", "parties_provider_name");
        checks.add(check("parties_customer_role", customer != null));
        addTextCheck(checks, customer,
                "name", "Fictional Test Customer", "parties_customer_name");
        addTextCheck(checks, candidate, "start_date", "2026-11-01", "start_date");
        addTextCheck(checks, candidate, "end_date", "2028-10-31", "end_date");
        checks.add(check("duration_months", candidate.path("duration_months").isIntegralNumber()
                && candidate.path("duration_months").intValue() == 24));
        addTextCheck(checks, candidate, "renewal_type", "FIXED_TERM", "renewal_type");
        addTextCheck(checks, candidate, "pricing_model", "FIXED", "pricing_model");

        JsonNode electricityData = candidate.path("electricity_data");
        JsonNode pricing = electricityData.path("pricing");
        addTextCheck(checks, pricing, "model", "FIXED", "electricity_data.pricing.model");
        JsonNode rates = pricing.path("rates");
        if (!rates.isArray()) {
            checks.add(check("electricity_data.pricing.rates_count", false));
            addRateNotEvaluated(checks, "rates_normal_day");
            addRateNotEvaluated(checks, "rates_off_peak_night");
        } else {
            checks.add(check("electricity_data.pricing.rates_count", rates.size() == 2));
            addRateChecks(checks, rates, "rates_normal_day", "0.12345", "normal/day");
            addRateChecks(checks, rates, "rates_off_peak_night", "0.09876", "off-peak/night");
        }

        JsonNode charges = electricityData.path("fixed_charges");
        JsonNode charge = charges.isArray() && charges.size() == 1 ? charges.get(0) : null;
        checks.add(check("fixed_charges_count", charge != null));
        if (charge == null) {
            checks.add(new ExpectedValueCheck("fixed_charges_type", ExpectedValueStatus.NOT_EVALUATED));
            checks.add(new ExpectedValueCheck("fixed_charges_amount", ExpectedValueStatus.NOT_EVALUATED));
            checks.add(new ExpectedValueCheck("fixed_charges_currency", ExpectedValueStatus.NOT_EVALUATED));
            checks.add(new ExpectedValueCheck("fixed_charges_period", ExpectedValueStatus.NOT_EVALUATED));
        } else {
            addTextCheck(checks, charge, "type", "SUPPLY", "fixed_charges_type");
            addNumberCheck(checks, charge, "amount", "8.76", "fixed_charges_amount");
            addTextCheck(checks, charge, "currency", "EUR", "fixed_charges_currency");
            checks.add(new ExpectedValueCheck("fixed_charges_period",
                    canonicalMonthlyPeriod(charge.path("period"))
                            ? ExpectedValueStatus.MATCH : ExpectedValueStatus.MISMATCH));
        }
        return new CandidateExpectationReport(List.copyOf(checks));
    }

    private static CandidateExpectationReport tariffOnlyDateAndChargeChecks(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) {
            return new CandidateExpectationReport(List.of(
                    new ExpectedValueCheck("candidate", ExpectedValueStatus.UNAVAILABLE)));
        }
        List<ExpectedValueCheck> checks = new ArrayList<>();
        for (String field : List.of("start_date", "end_date", "duration_months")) {
            JsonNode value = candidate.get(field);
            checks.add(new ExpectedValueCheck(field,
                    value == null || value.isNull() ? ExpectedValueStatus.MATCH : ExpectedValueStatus.MISMATCH));
        }
        JsonNode charges = candidate.path("electricity_data").path("fixed_charges");
        JsonNode charge = charges.isArray() && charges.size() == 1 ? charges.get(0) : null;
        addTextCheck(checks, candidate, "pricing_model", "VARIABLE", "pricing_model");
        addTextCheck(checks, candidate.path("electricity_data").path("pricing"), "model", "VARIABLE",
                "electricity_data.pricing.model");
        checks.add(check("fixed_charges_only_supported_supply", charge != null));
        addTextCheck(checks, charge, "type", "SUPPLY", "fixed_charges_type");
        addNumberCheck(checks, charge, "amount", "9.50", "fixed_charges_amount");
        addTextCheck(checks, charge, "currency", "EUR", "fixed_charges_currency");
        checks.add(new ExpectedValueCheck("fixed_charges_period",
                canonicalMonthlyPeriod(charge == null ? null : charge.path("period"))
                        ? ExpectedValueStatus.MATCH : ExpectedValueStatus.MISMATCH));
        return new CandidateExpectationReport(List.copyOf(checks));
    }

    private static void addRateChecks(
            List<ExpectedValueCheck> checks, JsonNode rates, String field, String expectedValue,
            String expectedTimePeriod) {
        JsonNode match = null;
        int matchCount = 0;
        java.math.BigDecimal expected = new java.math.BigDecimal(expectedValue);
        for (JsonNode rate : rates) {
            JsonNode value = rate.path("value");
            if (rate.isObject() && value.isNumber() && expected.compareTo(value.decimalValue()) == 0) {
                match = rate;
                matchCount++;
            }
        }
        String valueField = field + "_value";
        if (matchCount != 1) {
            checks.add(new ExpectedValueCheck(valueField, ExpectedValueStatus.MISMATCH));
            checks.add(new ExpectedValueCheck(field + "_time_period", ExpectedValueStatus.NOT_EVALUATED));
            checks.add(new ExpectedValueCheck(field + "_unit", ExpectedValueStatus.NOT_EVALUATED));
            checks.add(new ExpectedValueCheck(field + "_currency", ExpectedValueStatus.NOT_EVALUATED));
            checks.add(new ExpectedValueCheck(field + "_period", ExpectedValueStatus.NOT_EVALUATED));
            return;
        }
        checks.add(new ExpectedValueCheck(valueField, ExpectedValueStatus.MATCH));
        checks.add(new ExpectedValueCheck(field + "_time_period",
                canonicalTimeBand(match.path("time_period"), expectedTimePeriod)
                        ? ExpectedValueStatus.MATCH : ExpectedValueStatus.MISMATCH));
        addTextCheck(checks, match, "unit", "kWh", field + "_unit");
        addTextCheck(checks, match, "currency", "EUR", field + "_currency");
        checks.add(new ExpectedValueCheck(field + "_period",
                match.has("period") ? ExpectedValueStatus.MISMATCH : ExpectedValueStatus.MATCH));
    }

    private static void addRateNotEvaluated(List<ExpectedValueCheck> checks, String field) {
        checks.add(new ExpectedValueCheck(field + "_value", ExpectedValueStatus.MISMATCH));
        checks.add(new ExpectedValueCheck(field + "_time_period", ExpectedValueStatus.NOT_EVALUATED));
        checks.add(new ExpectedValueCheck(field + "_unit", ExpectedValueStatus.NOT_EVALUATED));
        checks.add(new ExpectedValueCheck(field + "_currency", ExpectedValueStatus.NOT_EVALUATED));
        checks.add(new ExpectedValueCheck(field + "_period", ExpectedValueStatus.NOT_EVALUATED));
    }

    private static void addTextCheck(
            List<ExpectedValueCheck> checks, JsonNode parent, String property, String expected, String field) {
        JsonNode actual = parent == null ? null : parent.get(property);
        checks.add(new ExpectedValueCheck(field, actual != null && actual.isTextual()
                && expected.equals(actual.textValue()) ? ExpectedValueStatus.MATCH : ExpectedValueStatus.MISMATCH));
    }

    private static void addNumberCheck(
            List<ExpectedValueCheck> checks, JsonNode parent, String property, String expected, String field) {
        JsonNode actual = parent == null ? null : parent.get(property);
        checks.add(new ExpectedValueCheck(field, actual != null && actual.isNumber()
                && new java.math.BigDecimal(expected).compareTo(actual.decimalValue()) == 0
                ? ExpectedValueStatus.MATCH : ExpectedValueStatus.MISMATCH));
    }

    private static ExpectedValueCheck check(String field, boolean matches) {
        return new ExpectedValueCheck(field,
                matches ? ExpectedValueStatus.MATCH : ExpectedValueStatus.MISMATCH);
    }

    private static boolean canonicalTimeBand(JsonNode actual, String expected) {
        if (actual == null || !actual.isTextual()) return false;
        String value = actual.textValue().strip().toLowerCase(java.util.Locale.ROOT);
        String canonicalActual = switch (value) {
            case "normal/day", "day", "daytime" -> "day";
            case "off-peak/night", "night", "overnight" -> "night";
            default -> value;
        };
        String canonicalExpected = expected.equals("normal/day") ? "day" : "night";
        return canonicalExpected.equals(canonicalActual);
    }

    private static boolean canonicalMonthlyPeriod(JsonNode actual) {
        if (actual == null || !actual.isTextual()) return false;
        return switch (actual.textValue().strip().toLowerCase(java.util.Locale.ROOT)) {
            case "month", "monthly", "per month" -> true;
            default -> false;
        };
    }

    private static JsonNode arrayObjectWithText(JsonNode array, String property, String expected) {
        if (array == null || !array.isArray()) return null;
        for (JsonNode item : array) {
            if (item.isObject() && expected.equals(item.path(property).textValue())) return item;
        }
        return null;
    }

    @Test
    void synthetic216ExpectedValuesMatchCanonicalLabelsAndRatesIndependentOfOrder() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode candidate = synthetic216ExpectedCandidate(json);

        assertTrue(synthetic216CandidateChecks(candidate).allMatched());

        JsonNode reversed = candidate.deepCopy();
        com.fasterxml.jackson.databind.node.ArrayNode rates = (com.fasterxml.jackson.databind.node.ArrayNode)
                reversed.path("electricity_data").path("pricing").path("rates");
        JsonNode first = rates.get(0).deepCopy();
        rates.set(0, rates.get(1).deepCopy());
        rates.set(1, first);
        assertTrue(synthetic216CandidateChecks(reversed).allMatched(),
                "exact amounts must associate expected rates regardless of array order");

        JsonNode equivalentLabels = candidate.deepCopy();
        com.fasterxml.jackson.databind.node.ArrayNode equivalentRates = (com.fasterxml.jackson.databind.node.ArrayNode)
                equivalentLabels.path("electricity_data").path("pricing").path("rates");
        ((com.fasterxml.jackson.databind.node.ObjectNode) equivalentRates.get(0)).put("time_period", "DAY");
        ((com.fasterxml.jackson.databind.node.ObjectNode) equivalentRates.get(1)).put("time_period", "overnight");
        ((com.fasterxml.jackson.databind.node.ObjectNode) equivalentLabels.path("electricity_data")
                .path("fixed_charges").get(0)).put("period", "per month");
        assertTrue(synthetic216CandidateChecks(equivalentLabels).allMatched(),
                "only the fixture's explicit day/night and monthly equivalents are accepted");
    }

    @Test
    void synthetic216ExpectedChecksDoNotCascadeWhenRateLabelOrAmountDiffers() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode candidate = synthetic216ExpectedCandidate(json);
        com.fasterxml.jackson.databind.node.ObjectNode dayRate = (com.fasterxml.jackson.databind.node.ObjectNode)
                candidate.path("electricity_data").path("pricing").path("rates").get(0);
        dayRate.put("time_period", "evening");

        CandidateExpectationReport wrongLabel = synthetic216CandidateChecks(candidate);
        assertEquals(ExpectedValueStatus.MISMATCH, status(wrongLabel, "rates_normal_day_time_period"));
        assertEquals(ExpectedValueStatus.MATCH, status(wrongLabel, "rates_normal_day_value"));
        assertEquals(ExpectedValueStatus.MATCH, status(wrongLabel, "rates_normal_day_unit"));
        assertEquals(ExpectedValueStatus.MATCH, status(wrongLabel, "rates_normal_day_currency"));
        assertEquals(ExpectedValueStatus.MATCH, status(wrongLabel, "rates_normal_day_period"));

        dayRate.put("value", 0.12346);
        CandidateExpectationReport wrongAmount = synthetic216CandidateChecks(candidate);
        assertEquals(ExpectedValueStatus.MISMATCH, status(wrongAmount, "rates_normal_day_value"),
                "per-kWh amounts are compared exactly, without rounding");
        assertEquals(ExpectedValueStatus.NOT_EVALUATED, status(wrongAmount, "rates_normal_day_time_period"));
        assertEquals(ExpectedValueStatus.NOT_EVALUATED, status(wrongAmount, "rates_normal_day_unit"));
        assertEquals(ExpectedValueStatus.NOT_EVALUATED, status(wrongAmount, "rates_normal_day_currency"));
        assertEquals(ExpectedValueStatus.NOT_EVALUATED, status(wrongAmount, "rates_normal_day_period"));
        assertEquals(ExpectedValueStatus.MATCH, status(wrongAmount, "rates_off_peak_night_value"));
    }

    @Test
    void synthetic216ExpectedChecksRejectDuplicateRateAmountsAsAmbiguous() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode candidate = synthetic216ExpectedCandidate(json);
        com.fasterxml.jackson.databind.node.ArrayNode rates = (com.fasterxml.jackson.databind.node.ArrayNode)
                candidate.path("electricity_data").path("pricing").path("rates");
        ((com.fasterxml.jackson.databind.node.ObjectNode) rates.get(1)).put("value", 0.12345);

        CandidateExpectationReport report = synthetic216CandidateChecks(candidate);

        assertEquals(ExpectedValueStatus.MISMATCH, status(report, "rates_normal_day_value"),
                "a duplicate exact amount is ambiguous and must not associate either row");
        assertEquals(ExpectedValueStatus.NOT_EVALUATED, status(report, "rates_normal_day_time_period"));
        assertEquals(ExpectedValueStatus.NOT_EVALUATED, status(report, "rates_normal_day_unit"));
        assertEquals(ExpectedValueStatus.NOT_EVALUATED, status(report, "rates_normal_day_currency"));
        assertEquals(ExpectedValueStatus.NOT_EVALUATED, status(report, "rates_normal_day_period"));
        assertEquals(ExpectedValueStatus.MISMATCH, status(report, "rates_off_peak_night_value"));
        assertEquals(ExpectedValueStatus.NOT_EVALUATED, status(report, "rates_off_peak_night_time_period"));
        assertEquals(ExpectedValueStatus.NOT_EVALUATED, status(report, "rates_off_peak_night_unit"));
        assertEquals(ExpectedValueStatus.NOT_EVALUATED, status(report, "rates_off_peak_night_currency"));
        assertEquals(ExpectedValueStatus.NOT_EVALUATED, status(report, "rates_off_peak_night_period"));
    }

    @Test
    void synthetic216ExpectedChecksRejectMissingTermsAndUnsupportedRatePeriod() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode expected = synthetic216ExpectedCandidate(json);

        for (String explicitTerm : List.of("start_date", "end_date", "duration_months", "renewal_type")) {
            JsonNode missingTerm = expected.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) missingTerm).remove(explicitTerm);
            assertEquals(ExpectedValueStatus.MISMATCH, status(synthetic216CandidateChecks(missingTerm), explicitTerm),
                    "the PDF expectation must fail when " + explicitTerm + " is omitted");
        }
        JsonNode missingRootPricingModel = expected.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingRootPricingModel).remove("pricing_model");
        assertEquals(ExpectedValueStatus.MISMATCH,
                status(synthetic216CandidateChecks(missingRootPricingModel), "pricing_model"));

        JsonNode monthlyRate = expected.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) monthlyRate.path("electricity_data").path("pricing")
                .path("rates").get(0)).put("period", "MONTHLY");
        assertEquals(ExpectedValueStatus.MISMATCH,
                status(synthetic216CandidateChecks(monthlyRate), "rates_normal_day_period"),
                "a monthly fixed-charge period must not be copied to an unstated rate period");

        assertEquals(ExpectedValueStatus.UNAVAILABLE, status(synthetic216CandidateChecks(null), "candidate"));
    }

    @Test
    void tariffOnlyDatesStayOutOfContractFieldsAndNetworkChargesStayOutOfSupplyCharges() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode expected = json.readTree("""
                {"schema_version":"1","parties":[],"pricing_model":"VARIABLE","electricity_data":{
                  "pricing":{"model":"VARIABLE"},"fixed_charges":[
                  {"type":"SUPPLY","amount":9.50,"currency":"EUR","period":"MONTHLY"}]}}
                """);
        assertTrue(tariffOnlyDateAndChargeChecks(expected).allMatched());

        JsonNode tariffDateMappedToContract = expected.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) tariffDateMappedToContract).put("start_date", "2024-01-01");
        assertEquals(ExpectedValueStatus.MISMATCH,
                status(tariffOnlyDateAndChargeChecks(tariffDateMappedToContract), "start_date"));

        JsonNode networkChargeLabeledAsSupply = expected.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) networkChargeLabeledAsSupply.path("electricity_data")
                .path("fixed_charges")).add(json.readTree(
                        "{\"type\":\"SUPPLY\",\"amount\":21.00,\"currency\":\"EUR\",\"period\":\"MONTHLY\"}"));
        assertEquals(ExpectedValueStatus.MISMATCH,
                status(tariffOnlyDateAndChargeChecks(networkChargeLabeledAsSupply),
                        "fixed_charges_only_supported_supply"));

        JsonNode missingNestedModel = expected.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingNestedModel.path("electricity_data")
                .path("pricing")).remove("model");
        assertEquals(ExpectedValueStatus.MISMATCH,
                status(tariffOnlyDateAndChargeChecks(missingNestedModel), "electricity_data.pricing.model"));

        JsonNode missingRootModel = expected.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingRootModel).remove("pricing_model");
        assertEquals(ExpectedValueStatus.MISMATCH,
                status(tariffOnlyDateAndChargeChecks(missingRootModel), "pricing_model"));
    }

    @Test
    void approvedNormalizersDoNotAcceptNullOptionalObjectOrArrayStructures() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode schema = loadSchema(json);
        JsonNode candidate = json.readTree("""
                {"schema_version":"1","parties":[],"electricity_data":{
                  "consumption":null,"discounts":null,"return_delivery":null}}
                """);

        BedrockContractExtractionAdapter.normalizeOptionalAnnualKwhNull(candidate);
        BedrockContractExtractionAdapter.normalizeOptionalPricingRateNulls(candidate);

        List<SyntheticSchemaViolationCollector.Violation> violations =
                SyntheticSchemaViolationCollector.collect(candidate, schema);
        assertEquals(3, violations.size());
        assertTrue(violations.stream().anyMatch(v -> v.safeSummary().contains("consumption")));
        assertTrue(violations.stream().anyMatch(v -> v.safeSummary().contains("discounts")));
        assertTrue(violations.stream().anyMatch(v -> v.safeSummary().contains("return_delivery")));
    }

    private static JsonNode synthetic216ExpectedCandidate(ObjectMapper json) throws Exception {
        return json.readTree("""
                {
                  "schema_version": "1",
                  "parties": [
                    {"role": "PROVIDER", "name": "Fictional Test Energy B.V."},
                    {"role": "CUSTOMER", "name": "Fictional Test Customer"}
                  ],
                  "start_date": "2026-11-01",
                  "end_date": "2028-10-31",
                  "duration_months": 24,
                  "renewal_type": "FIXED_TERM",
                  "pricing_model": "FIXED",
                  "electricity_data": {
                    "pricing": {
                      "model": "FIXED",
                      "rates": [
                        {"value": 0.12345, "unit": "kWh", "currency": "EUR", "time_period": "Normal/day"},
                        {"value": 0.09876, "unit": "kWh", "currency": "EUR", "time_period": "Off-peak/night"}
                      ]
                    },
                    "fixed_charges": [
                      {"type": "SUPPLY", "amount": 8.76, "currency": "EUR", "period": "MONTHLY"}
                    ]
                  }
                }
                """);
    }

    private static ExpectedValueStatus status(CandidateExpectationReport report, String field) {
        return report.checks().stream().filter(check -> check.field().equals(field))
                .map(ExpectedValueCheck::status).findFirst().orElseThrow();
    }

    private static void traceCandidate(String stage, String fixture, JsonNode root) {
        if (root == null || !root.isObject()) {
            System.out.println("synthetic_pdf_trace fixture=" + fixture + " stage=" + stage
                    + " candidate=unavailable");
            return;
        }
        System.out.println("synthetic_pdf_trace fixture=" + fixture + " stage=" + stage
                + " start_date=" + selectedValue(root, "start_date")
                + " end_date=" + selectedValue(root, "end_date")
                + " duration_months=" + selectedValue(root, "duration_months")
                + " renewal_type=" + selectedValue(root, "renewal_type")
                + " pricing_model=" + selectedValue(root, "pricing_model"));
        traceElectricity(fixture, stage, root.get("electricity_data"));
    }

    private static void traceMappedAggregate(
            String fixture,
            io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate aggregate,
            ObjectMapper json) throws JsonProcessingException {
        if (aggregate == null || aggregate.terms() == null) {
            System.out.println("synthetic_pdf_trace fixture=" + fixture
                    + " stage=mapped_aggregate terms=unavailable");
            return;
        }
        var terms = aggregate.terms();
        System.out.println("synthetic_pdf_trace fixture=" + fixture + " stage=mapped_aggregate"
                + " start_date=" + (terms.startDate() == null ? "missing" : "present")
                + " end_date=" + (terms.endDate() == null ? "missing" : "present")
                + " duration_months=" + (terms.contractDuration() == null ? "missing" : "present")
                + " renewal_type=" + (terms.renewalType() == null ? "missing" : "present")
                + " pricing_model=" + (terms.pricingModel() == null ? "missing" : "present"));
        JsonNode electricityData = terms.electricityData() == null
                ? null : json.readTree(terms.electricityData().value());
        traceElectricity(fixture, "mapped_aggregate", electricityData);
    }

    private static void traceElectricity(String fixture, String stage, JsonNode electricityData) {
        JsonNode pricing = electricityData == null ? null : electricityData.path("pricing");
        JsonNode rates = pricing == null ? null : pricing.get("rates");
        int rateCount = rates != null && rates.isArray() ? rates.size() : -1;
        System.out.println("synthetic_pdf_trace fixture=" + fixture + " stage=" + stage
                + " pricing_model=" + selectedValue(pricing, "model")
                + " consumption=" + fieldState(electricityData, "consumption")
                + " return_delivery=" + fieldState(electricityData, "return_delivery")
                + " discounts=" + fieldState(electricityData, "discounts")
                + " rate_count=" + (rateCount < 0 ? "unavailable" : rateCount));
        if (rates != null && rates.isArray()) {
            for (int index = 0; index < Math.min(rates.size(), 4); index++) {
                JsonNode rate = rates.get(index);
                System.out.println("synthetic_pdf_trace fixture=" + fixture + " stage=" + stage
                        + " rate_index=" + index
                        + " value=" + selectedValue(rate, "value")
                        + " unit=" + selectedValue(rate, "unit")
                        + " currency=" + selectedValue(rate, "currency")
                        + " period=" + selectedValue(rate, "period")
                        + " time_period=" + selectedValue(rate, "time_period")
                        + " index=" + selectedValue(rate, "index"));
            }
        }
        JsonNode charges = electricityData == null ? null : electricityData.get("fixed_charges");
        if (charges != null && charges.isArray()) {
            for (int index = 0; index < Math.min(charges.size(), 3); index++) {
                JsonNode charge = charges.get(index);
                System.out.println("synthetic_pdf_trace fixture=" + fixture + " stage=" + stage
                        + " fixed_charge_index=" + index
                        + " type=" + selectedValue(charge, "type")
                        + " amount=" + selectedValue(charge, "amount")
                        + " currency=" + selectedValue(charge, "currency")
                        + " period=" + selectedValue(charge, "period"));
            }
        }
    }

    private static String selectedValue(JsonNode parent, String property) {
        if (parent == null) return "parent_missing";
        JsonNode value = parent.get(property);
        if (value == null) return "missing";
        if (value.isNull()) return "null";
        if (!value.isValueNode()) return value.isArray() ? "array" : "object";
        if (value.isTextual()) return boundedQuoted(value.textValue());
        String rendered = value.toString();
        return rendered.length() <= 80 ? rendered : rendered.substring(0, 80) + "...";
    }

    private static String boundedQuoted(String value) {
        StringBuilder result = new StringBuilder("\"");
        int remaining = 80;
        boolean truncated = false;
        for (int index = 0; index < value.length() && remaining > 0; index++) {
            char character = value.charAt(index);
            if (character == '\\' || character == '"') {
                result.append('\\').append(character);
            } else if (Character.isISOControl(character)) {
                result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
            } else {
                result.append(character);
            }
            remaining--;
        }
        if (value.length() > 80) truncated = true;
        if (truncated) result.append("...");
        return result.append('"').toString();
    }

    private static String fieldState(JsonNode parent, String property) {
        if (parent == null) return "parent_missing";
        JsonNode value = parent.get(property);
        if (value == null) return "missing";
        if (value.isNull()) return "null";
        return value.isValueNode() ? "present" : value.isArray() ? "array" : "object";
    }

    @Test
    void reportsFailureWhenSchemaValidSyntheticResponseFailsBusinessValidation() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode schema = loadSchema(json);
        String providerText = """
                {
                  "schema_version": "1",
                  "parties": [{"role": "CUSTOMER", "name": "Synthetic Buyer"}],
                  "duration_months": -1,
                  "electricity_data": {}
                }
                """;
        ConverseResponse response = ConverseResponse.builder()
                .output(output -> output.message(message -> message.role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.builder().text(providerText).build())))
                .usage(TokenUsage.builder().inputTokens(3).outputTokens(4).totalTokens(7).build())
                .stopReason(StopReason.END_TURN)
                .build();
        BedrockRuntimeClient client = (BedrockRuntimeClient) Proxy.newProxyInstance(
                BedrockRuntimeClient.class.getClassLoader(), new Class<?>[] {BedrockRuntimeClient.class},
                (proxy, method, args) -> method.getName().equals("converse") ? response : null);
        BedrockProperties properties = new BedrockProperties("eu-west-1", "eu.amazon.nova-2-lite-v1:0", 100,
                Duration.ofSeconds(1));
        RecordingAccounting accounting = new RecordingAccounting();
        var workService = new ElectricityContractProcessingWorkService(new SyntheticTextExtraction(SYNTHETIC_DOCUMENT),
                new ModelInputPreparationService(new TextExtractionProperties(0, 0, 0, 0, 200_000)),
                new BedrockContractExtractionAdapter(client, json, properties),
                new ElectricityContractCandidateValidator(json), accounting,
                new ContractExtractionAccountingProperties(properties.modelId(), "EUR"),
                (provider, service, dimension, usage, occurredAt) -> CostCalculation.unknown("EUR"), json);
        Document syntheticDocument = new Document(SYNTHETIC_DOCUMENT_ID, SYNTHETIC_USER_ID,
                "synthetic-only", "synthetic-contract.pdf", "application/pdf", 1,
                DocumentStatus.PROCESSING, OffsetDateTime.now(), OffsetDateTime.now());

        PermanentProcessingException failure = org.junit.jupiter.api.Assertions.assertThrows(
                PermanentProcessingException.class, () -> workService.process(syntheticDocument));

        JsonNode candidate = parseCandidate(json, providerText);
        List<SyntheticSchemaViolationCollector.Violation> violations =
                SyntheticSchemaViolationCollector.collect(candidate, schema);
        ProbeProcessingReport report = processingReport(false, failure, true, violations);

        assertTrue(violations.isEmpty(), "Synthetic response must pass the extraction schema");
        assertEquals(1, accounting.events.size(), "Provider usage must still be accounted on business failure");
        assertEquals(DiagnosticCompletion.COMPLETE, report.diagnosticCompletion());
        assertEquals(ProcessingOutcome.FAILED, report.outcome());
        assertEquals(ProcessingFailureDiagnostics.Stage.BUSINESS_VALIDATION, report.failureStage());
        assertEquals(ProcessingFailureDiagnostics.ExceptionCategory.INVALID_CANDIDATE,
                report.failureCategory());
    }

    @Test
    void classifiesCheckedConverseFailureWrappedByDynamicProxyWithoutMessages() {
        RuntimeException failure = converseProxyFailure(new UnknownHostException("PRIVATE_SENTINEL"));

        assertTrue(failure instanceof UndeclaredThrowableException,
                "A checked exception from the proxy handler must be wrapped by the JDK proxy");
        ProbeFailure classified = classifyFailure(failure, List.of());

        assertEquals(FailureKind.NETWORK_DNS_TLS, classified.failureKind());
        assertEquals("UndeclaredThrowableException,UnknownHostException", classified.exceptionClasses());
        assertEquals("none", classified.awsErrorCode());
        assertTrue(!classified.safeSummary().contains("PRIVATE_SENTINEL"));
    }

    @Test
    void dynamicProxyPassesUncheckedBedrockSdkExceptionsThroughUnwrapped() {
        RuntimeException serviceFailure = AccessDeniedException.builder().message("PRIVATE_SENTINEL")
                .statusCode(403).build();

        RuntimeException failure = converseProxyFailure(serviceFailure);

        assertTrue(failure instanceof AccessDeniedException,
                "Unchecked AWS SDK exceptions pass through a JDK proxy without UndeclaredThrowableException");
        assertTrue(!(failure instanceof UndeclaredThrowableException));
        assertEquals(FailureKind.SERVICE_DENIAL, classifyFailure(failure, List.of()).failureKind());
    }

    @Test
    void classifiesCredentialResolutionSeparately() {
        CredentialResolutionFailure failure = org.junit.jupiter.api.Assertions.assertThrows(
                CredentialResolutionFailure.class,
                () -> preflightCredentials(() -> { throw SdkClientException.create("PRIVATE_SENTINEL"); }));

        ProbeFailure classified = classifyFailure(failure, List.of());

        assertEquals(FailureKind.CREDENTIAL_RESOLUTION, classified.failureKind());
        assertEquals("CredentialResolutionFailure,SdkClientException", classified.exceptionClasses());
        assertTrue(!classified.safeSummary().contains("PRIVATE_SENTINEL"));
    }

    @Test
    void classifiesSdkTimeoutSeparately() {
        RuntimeException failure = ApiCallAttemptTimeoutException.builder().message("PRIVATE_SENTINEL").build();

        ProbeFailure classified = classifyFailure(failure, List.of());

        assertEquals(FailureKind.TIMEOUT, classified.failureKind());
        assertEquals("ApiCallAttemptTimeoutException", classified.exceptionClasses());
        assertEquals("none", classified.awsErrorCode());
        assertTrue(!classified.safeSummary().contains("PRIVATE_SENTINEL"));
    }

    @Test
    void classifiesBedrockDenialAndOnlyEmitsAllowlistedAwsCodes() {
        RuntimeException denial = AccessDeniedException.builder().message("PRIVATE_SENTINEL").statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().serviceName("BedrockRuntime")
                        .errorCode("AccessDeniedException").build())
                .build();
        ProbeFailure classifiedDenial = classifyFailure(denial, List.of());

        assertEquals(FailureKind.SERVICE_DENIAL, classifiedDenial.failureKind());
        assertEquals("AccessDeniedException", classifiedDenial.awsErrorCode());
        assertTrue(!classifiedDenial.safeSummary().contains("PRIVATE_SENTINEL"));

        RuntimeException unknownCode = AccessDeniedException.builder().message("PRIVATE_SENTINEL").statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().serviceName("BedrockRuntime")
                        .errorCode("PRIVATE_CUSTOM_ERROR_CODE").build())
                .build();
        ProbeFailure classifiedUnknownCode = classifyFailure(unknownCode, List.of());

        assertEquals(FailureKind.SERVICE_DENIAL, classifiedUnknownCode.failureKind());
        assertEquals("other", classifiedUnknownCode.awsErrorCode());
        assertTrue(!classifiedUnknownCode.safeSummary().contains("PRIVATE_CUSTOM_ERROR_CODE"));
    }

    @Test
    void leavesUnknownRuntimeFailuresInOtherAndDoesNotExposeMessages() {
        ProbeFailure classified = classifyFailure(new IllegalStateException("PRIVATE_SENTINEL"), List.of());

        assertEquals(FailureKind.OTHER, classified.failureKind());
        assertEquals("IllegalStateException", classified.exceptionClasses());
        assertEquals("none", classified.awsErrorCode());
        assertTrue(!classified.safeSummary().contains("PRIVATE_SENTINEL"));
    }

    private static RuntimeException converseProxyFailure(Throwable thrown) {
        BedrockRuntimeClient client = (BedrockRuntimeClient) Proxy.newProxyInstance(
                BedrockRuntimeClient.class.getClassLoader(), new Class<?>[] {BedrockRuntimeClient.class},
                (proxy, method, args) -> { throw thrown; });
        try {
            client.converse(ConverseRequest.builder().modelId("synthetic-model").build());
            throw new AssertionError("Stub proxy unexpectedly returned a Converse response");
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private static void preflightCredentials(Runnable resolver) {
        try {
            resolver.run();
        } catch (RuntimeException failure) {
            throw new CredentialResolutionFailure(failure);
        }
    }

    private static ProbeProcessingReport processingReport(
            boolean processingSucceeded,
            RuntimeException processingFailure,
            boolean responseReceived,
            List<SyntheticSchemaViolationCollector.Violation> violations) {
        DiagnosticCompletion completion = responseReceived
                ? DiagnosticCompletion.COMPLETE : DiagnosticCompletion.RESPONSE_UNAVAILABLE;
        if (processingSucceeded && responseReceived && processingFailure == null) {
            return new ProbeProcessingReport(completion, ProcessingOutcome.SUCCEEDED, null, null,
                    FailureKind.OTHER, "none", "none");
        }
        if (processingFailure == null) {
            return new ProbeProcessingReport(completion, ProcessingOutcome.NOT_CONFIRMED,
                    ProcessingFailureDiagnostics.Stage.OTHER_RUNTIME,
                    ProcessingFailureDiagnostics.ExceptionCategory.OTHER_RUNTIME,
                    FailureKind.OTHER, "none", "none");
        }
        ProbeFailure failure = classifyFailure(processingFailure, violations);
        return new ProbeProcessingReport(completion, ProcessingOutcome.FAILED,
                failure.stage(), failure.category(), failure.failureKind(), failure.exceptionClasses(),
                failure.awsErrorCode());
    }

    private static ProbeFailure classifyFailure(
            Throwable failure, List<SyntheticSchemaViolationCollector.Violation> violations) {
        List<Throwable> causes = causeChain(failure);
        for (Throwable cause : causes) {
            if (cause instanceof CredentialResolutionFailure) {
                return probeFailure(ProcessingFailureDiagnostics.Stage.BEDROCK_CONVERSE,
                        ProcessingFailureDiagnostics.ExceptionCategory.OTHER_RUNTIME,
                        FailureKind.CREDENTIAL_RESOLUTION, causes, "none");
            }
        }
        for (Throwable cause : causes) {
            if (cause instanceof ElectricityContractCandidateValidator.InvalidCandidateException) {
                return probeFailure(ProcessingFailureDiagnostics.Stage.BUSINESS_VALIDATION,
                        ProcessingFailureDiagnostics.ExceptionCategory.INVALID_CANDIDATE, FailureKind.OTHER,
                        causes, "none");
            }
            if (cause instanceof JsonProcessingException) {
                return probeFailure(ProcessingFailureDiagnostics.Stage.JSON_PARSING,
                        ProcessingFailureDiagnostics.ExceptionCategory.JSON_PROCESSING, FailureKind.OTHER,
                        causes, "none");
            }
            if (cause instanceof DateTimeException) {
                return probeFailure(ProcessingFailureDiagnostics.Stage.CANDIDATE_MAPPING,
                        ProcessingFailureDiagnostics.ExceptionCategory.DATE_TIME, FailureKind.OTHER,
                        causes, "none");
            }
            if (cause instanceof NoSuchElementException) {
                return probeFailure(ProcessingFailureDiagnostics.Stage.BEDROCK_RESPONSE_EXTRACTION,
                        ProcessingFailureDiagnostics.ExceptionCategory.MISSING_VALUE, FailureKind.OTHER,
                        causes, "none");
            }
            if (cause instanceof BedrockRuntimeException) {
                BedrockRuntimeException serviceFailure = (BedrockRuntimeException) cause;
                String errorCode = safeAwsErrorCode(serviceFailure);
                FailureKind kind = serviceDenial(serviceFailure, errorCode)
                        ? FailureKind.SERVICE_DENIAL : FailureKind.OTHER;
                return probeFailure(ProcessingFailureDiagnostics.Stage.BEDROCK_CONVERSE,
                        ProcessingFailureDiagnostics.ExceptionCategory.BEDROCK_SERVICE, kind, causes, errorCode);
            }
        }
        for (Throwable cause : causes) {
            if (isTimeout(cause)) {
                return probeFailure(ProcessingFailureDiagnostics.Stage.BEDROCK_CONVERSE,
                        ProcessingFailureDiagnostics.ExceptionCategory.OTHER_RUNTIME, FailureKind.TIMEOUT,
                        causes, "none");
            }
        }
        for (Throwable cause : causes) {
            if (isNetworkFailure(cause)) {
                return probeFailure(ProcessingFailureDiagnostics.Stage.BEDROCK_CONVERSE,
                        ProcessingFailureDiagnostics.ExceptionCategory.OTHER_RUNTIME, FailureKind.NETWORK_DNS_TLS,
                        causes, "none");
            }
        }
        if (!violations.isEmpty()) {
            return probeFailure(ProcessingFailureDiagnostics.Stage.LOCAL_SCHEMA_VALIDATION,
                    ProcessingFailureDiagnostics.ExceptionCategory.ILLEGAL_ARGUMENT, FailureKind.OTHER,
                    causes, "none");
        }
        for (Throwable cause : causes) {
            if (cause instanceof SdkClientException) {
                return probeFailure(ProcessingFailureDiagnostics.Stage.BEDROCK_CONVERSE,
                        ProcessingFailureDiagnostics.ExceptionCategory.OTHER_RUNTIME, FailureKind.OTHER,
                        causes, "none");
            }
            if (cause instanceof IllegalArgumentException) {
                return probeFailure(ProcessingFailureDiagnostics.Stage.OTHER_RUNTIME,
                        ProcessingFailureDiagnostics.ExceptionCategory.ILLEGAL_ARGUMENT, FailureKind.OTHER,
                        causes, "none");
            }
            if (cause instanceof NullPointerException) {
                return probeFailure(ProcessingFailureDiagnostics.Stage.OTHER_RUNTIME,
                        ProcessingFailureDiagnostics.ExceptionCategory.NULL_POINTER, FailureKind.OTHER,
                        causes, "none");
            }
        }
        return probeFailure(ProcessingFailureDiagnostics.Stage.OTHER_RUNTIME,
                ProcessingFailureDiagnostics.ExceptionCategory.OTHER_RUNTIME, FailureKind.OTHER,
                causes, "none");
    }

    private static ProbeFailure probeFailure(ProcessingFailureDiagnostics.Stage stage,
            ProcessingFailureDiagnostics.ExceptionCategory category, FailureKind kind,
            List<Throwable> causes, String awsErrorCode) {
        return new ProbeFailure(stage, category, kind, safeExceptionClasses(causes), awsErrorCode);
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> causes = new ArrayList<>();
        IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
        Throwable current = failure;
        while (current != null && causes.size() < 16 && seen.put(current, Boolean.TRUE) == null) {
            causes.add(current);
            if (current instanceof UndeclaredThrowableException undeclared
                    && undeclared.getUndeclaredThrowable() != null) {
                current = undeclared.getUndeclaredThrowable();
            } else if (current instanceof InvocationTargetException invocation
                    && invocation.getTargetException() != null) {
                current = invocation.getTargetException();
            } else {
                current = current.getCause();
            }
        }
        return causes;
    }

    private static boolean isTimeout(Throwable failure) {
        return failure instanceof ApiCallTimeoutException
                || failure instanceof ApiCallAttemptTimeoutException
                || failure instanceof SocketTimeoutException
                || failure instanceof HttpTimeoutException
                || failure instanceof TimeoutException
                || failure.getClass().getName().equals("io.netty.handler.timeout.ReadTimeoutException")
                || failure.getClass().getName().equals("io.netty.handler.timeout.WriteTimeoutException");
    }

    private static boolean isNetworkFailure(Throwable failure) {
        return failure instanceof UnknownHostException
                || failure instanceof ConnectException
                || failure instanceof NoRouteToHostException
                || failure instanceof SocketException
                || failure instanceof SSLException
                || failure instanceof IOException
                || failure instanceof ClosedChannelException
                || failure instanceof UnresolvedAddressException
                || failure.getClass().getName().equals("io.netty.channel.ConnectTimeoutException");
    }

    private static boolean serviceDenial(BedrockRuntimeException failure, String errorCode) {
        return failure.statusCode() == 401 || failure.statusCode() == 403
                || Set.of("AccessDeniedException", "AccessDenied", "UnauthorizedException",
                        "UnrecognizedClientException", "InvalidSignatureException", "ExpiredTokenException")
                        .contains(errorCode);
    }

    private static String safeAwsErrorCode(BedrockRuntimeException failure) {
        if (failure.awsErrorDetails() == null || failure.awsErrorDetails().errorCode() == null) return "none";
        String code = failure.awsErrorDetails().errorCode();
        return Set.of("AccessDeniedException", "AccessDenied", "UnauthorizedException",
                        "UnrecognizedClientException", "InvalidSignatureException", "ExpiredTokenException",
                        "ThrottlingException", "ServiceUnavailableException", "InternalServerException",
                        "ModelNotReadyException", "ModelTimeoutException", "ModelErrorException",
                        "ResourceNotFoundException", "ServiceQuotaExceededException", "ValidationException")
                .contains(code) ? code : "other";
    }

    private static String safeExceptionClasses(List<Throwable> causes) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Throwable cause : causes) {
            String name = safeExceptionClass(cause);
            if (name != null) names.add(name);
            if (names.size() == 6) break;
        }
        return names.isEmpty() ? "other" : String.join(",", names);
    }

    private static String safeExceptionClass(Throwable failure) {
        if (failure instanceof UndeclaredThrowableException) return "UndeclaredThrowableException";
        if (failure instanceof InvocationTargetException) return "InvocationTargetException";
        if (failure instanceof CredentialResolutionFailure) return "CredentialResolutionFailure";
        if (failure instanceof AccessDeniedException) return "AccessDeniedException";
        if (failure instanceof BedrockRuntimeException) return "BedrockRuntimeException";
        if (failure instanceof ApiCallAttemptTimeoutException) return "ApiCallAttemptTimeoutException";
        if (failure instanceof ApiCallTimeoutException) return "ApiCallTimeoutException";
        if (failure instanceof SocketTimeoutException) return "SocketTimeoutException";
        if (failure instanceof HttpTimeoutException) return "HttpTimeoutException";
        if (failure instanceof TimeoutException) return "TimeoutException";
        if (failure instanceof SSLHandshakeException) return "SSLHandshakeException";
        if (failure instanceof SSLException) return "SSLException";
        if (failure instanceof UnknownHostException) return "UnknownHostException";
        if (failure instanceof ConnectException) return "ConnectException";
        if (failure instanceof NoRouteToHostException) return "NoRouteToHostException";
        if (failure instanceof SocketException) return "SocketException";
        if (failure instanceof ClosedChannelException) return "ClosedChannelException";
        if (failure instanceof UnresolvedAddressException) return "UnresolvedAddressException";
        if (failure instanceof IOException) return "IOException";
        if (failure instanceof SdkClientException) return "SdkClientException";
        if (failure.getClass() == IllegalStateException.class) return "IllegalStateException";
        if (failure.getClass() == IllegalArgumentException.class) return "IllegalArgumentException";
        if (failure.getClass() == NullPointerException.class) return "NullPointerException";
        if (failure.getClass() == RuntimeException.class) return "RuntimeException";
        if (failure.getClass().getName().equals("io.netty.handler.timeout.ReadTimeoutException")) {
            return "ReadTimeoutException";
        }
        if (failure.getClass().getName().equals("io.netty.handler.timeout.WriteTimeoutException")) {
            return "WriteTimeoutException";
        }
        if (failure.getClass().getName().equals("io.netty.channel.ConnectTimeoutException")) {
            return "ConnectTimeoutException";
        }
        return null;
    }

    private enum FailureKind {
        CREDENTIAL_RESOLUTION("credential_resolution"),
        NETWORK_DNS_TLS("network_dns_tls"),
        TIMEOUT("timeout"),
        SERVICE_DENIAL("service_denial"),
        OTHER("other");

        private final String value;
        FailureKind(String value) { this.value = value; }
        String value() { return value; }
    }

    private enum ExpectedValueStatus {
        MATCH("match"), MISMATCH("mismatch"), NOT_EVALUATED("not_evaluated"), UNAVAILABLE("unavailable");

        private final String value;
        ExpectedValueStatus(String value) { this.value = value; }
        String value() { return value; }
    }

    private record ExpectedValueCheck(String field, ExpectedValueStatus status) { }

    private record CandidateExpectationReport(List<ExpectedValueCheck> checks) {
        private static CandidateExpectationReport empty() {
            return new CandidateExpectationReport(List.of());
        }

        private long mismatchCount() {
            return checks.stream().filter(check -> check.status() == ExpectedValueStatus.MISMATCH).count();
        }

        private long notEvaluatedCount() {
            return checks.stream().filter(check -> check.status() == ExpectedValueStatus.NOT_EVALUATED).count();
        }

        private long unavailableCount() {
            return checks.stream().filter(check -> check.status() == ExpectedValueStatus.UNAVAILABLE).count();
        }

        private boolean allMatched() {
            return checks.stream().allMatch(check -> check.status() == ExpectedValueStatus.MATCH);
        }
    }

    private static final class CredentialResolutionFailure extends RuntimeException {
        private CredentialResolutionFailure(Throwable cause) { super(cause); }
    }

    private enum DiagnosticCompletion {
        COMPLETE("complete"), RESPONSE_UNAVAILABLE("response_unavailable");

        private final String value;
        DiagnosticCompletion(String value) { this.value = value; }
        String value() { return value; }
    }

    private enum ProcessingOutcome {
        SUCCEEDED("succeeded"), FAILED("failed"), NOT_CONFIRMED("not_confirmed");

        private final String value;
        ProcessingOutcome(String value) { this.value = value; }
        String value() { return value; }
    }

    private record ProbeFailure(ProcessingFailureDiagnostics.Stage stage,
                                ProcessingFailureDiagnostics.ExceptionCategory category,
                                FailureKind failureKind, String exceptionClasses, String awsErrorCode) {
        private String safeSummary() {
            return "failure_kind=" + failureKind.value() + " exception_classes=" + exceptionClasses
                    + " aws_error_code=" + awsErrorCode;
        }
    }

    private record ProbeProcessingReport(DiagnosticCompletion diagnosticCompletion, ProcessingOutcome outcome,
                                         ProcessingFailureDiagnostics.Stage failureStage,
                                         ProcessingFailureDiagnostics.ExceptionCategory failureCategory,
                                         FailureKind failureKind, String exceptionClasses, String awsErrorCode) {
        private String failureStageValue() {
            return failureStage == null ? "none" : failureStage.value();
        }

        private String failureCategoryValue() {
            return failureCategory == null ? "none" : failureCategory.value();
        }

        private String failureKindValue() { return failureKind.value(); }
    }

    private static JsonNode parseCandidate(ObjectMapper json, String providerText) {
        if (providerText == null) return null;
        try {
            return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(BedrockContractExtractionAdapter.unwrapSingleJsonCodeFence(providerText));
        } catch (JsonProcessingException | RuntimeException ignored) {
            return null;
        }
    }

    private static String actualShape(JsonNode node) {
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isNull()) return "null";
        return "unknown";
    }

    private static String stopReason(StopReason stopReason) {
        if (stopReason == null) return "unknown";
        return switch (stopReason.name()) {
            case "END_TURN" -> "end_turn";
            case "MAX_TOKENS" -> "max_tokens";
            case "STOP_SEQUENCE" -> "stop_sequence";
            case "TOOL_USE" -> "tool_use";
            case "GUARDRAIL_INTERVENED" -> "guardrail_intervened";
            case "CONTENT_FILTERED" -> "content_filtered";
            default -> "other";
        };
    }

    private static String outputTruncated(StopReason stopReason) {
        return stopReason == null ? "unknown" : Boolean.toString(stopReason == StopReason.MAX_TOKENS);
    }

    private static String tokenCount(Integer value) {
        return value == null || value < 0 ? "unknown" : value.toString();
    }

    private static JsonNode loadSchema(ObjectMapper json) throws Exception {
        return json.readTree(loadSchemaText());
    }

    private static String loadSchemaText() throws Exception {
        try (var resource = SyntheticBedrockSchemaProbeTests.class.getResourceAsStream(
                "/extraction/electricity-contract-candidate-v1.schema.json")) {
            assertNotNull(resource, "Local extraction schema resource is missing");
            return new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static final class SyntheticTextExtraction extends DocumentTextExtractionService {
        private final String text;

        private SyntheticTextExtraction(String text) {
            super(null, null, ignored -> new DirectTextExtractorPort.ExtractionResult(text, 1), null, null,
                    new TextExtractionProperties(0, 0, 0, 0, 200_000));
            this.text = text;
        }

        @Override public ExtractionResult extract(UUID documentId) {
            return new ExtractionResult(text, Source.DIRECT);
        }
    }

    private record SyntheticPricingContract(String label, String document, UUID documentId) { }

    private static final class RecordingAccounting implements AccountingPort {
        private final List<UsageEvent> events = new ArrayList<>();
        @Override public void publish(UsageEvent event) { events.add(event); }
    }
}
