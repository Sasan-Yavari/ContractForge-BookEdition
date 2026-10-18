package io.contractforge.backend.application.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.accounting.AccountingPort;
import io.contractforge.backend.application.port.RepositoryPorts;
import io.contractforge.backend.application.port.UploadObjectStoragePort;
import io.contractforge.backend.application.processing.PermanentProcessingException;
import io.contractforge.backend.application.processing.ProcessingErrorCode;
import io.contractforge.backend.application.processing.RetryableProcessingException;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.PersistenceModels.Document;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentTextExtractionServiceTests {
    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final TextExtractionProperties LIMITS = new TextExtractionProperties(1000, 100, 10, 5);

    @Test
    void sufficientDirectTextAvoidsTextractAndAccounting() {
        Fixture fixture = new Fixture(new DirectTextExtractorPort.ExtractionResult("local contract text", 1));

        DocumentTextExtractionService.ExtractionResult result = fixture.service().extract(DOCUMENT_ID);

        assertThat(result.source()).isEqualTo(DocumentTextExtractionService.Source.DIRECT);
        assertThat(result.text()).isEqualTo("local contract text");
        assertThat(fixture.ocr.calls).isZero();
        assertThat(fixture.accounting.events).isEmpty();
    }

    @Test
    void malformedPdfIsClassifiedAsDeterministicDocumentFailure() {
        Fixture fixture = new Fixture(new DirectTextExtractorPort.ExtractionResult("ignored", 1));
        DocumentTextExtractionService service = new DocumentTextExtractionService(fixture.documents, fixture.storage,
                ignored -> { throw new DirectTextExtractorPort.MalformedDocumentException(new IllegalArgumentException()); },
                fixture.ocr, fixture.accounting, LIMITS);

        assertThatThrownBy(() -> service.extract(DOCUMENT_ID))
                .isInstanceOfSatisfying(PermanentProcessingException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ProcessingErrorCode.MALFORMED_DOCUMENT));
        assertThat(fixture.ocr.calls).isZero();
    }

    @Test
    void oversizedButSafelyExtractableDirectTextIsPassedToModelInputPreparation() {
        String text = "safe extracted contract text\n".repeat(4_500);
        Fixture fixture = new Fixture(new DirectTextExtractorPort.ExtractionResult(text, 28));
        fixture.limits = new TextExtractionProperties(1000, 200_000, 10, 100, 200_000);

        DocumentTextExtractionService.ExtractionResult result = fixture.service().extract(DOCUMENT_ID);

        assertThat(result.text()).hasSize(text.length()).hasSizeGreaterThan(100_000);
        assertThat(result.source()).isEqualTo(DocumentTextExtractionService.Source.DIRECT);
        assertThat(fixture.ocr.calls).isZero();
    }

    @Test
    void insufficientDirectTextFallsBackToTextractAndPublishesOnePaidUsageEvent() {
        Fixture fixture = new Fixture(new DirectTextExtractorPort.ExtractionResult("", 1));
        fixture.ocr.result = new OcrTextExtractorPort.OcrResult("ocr text", new JsonValue("{\"pages\":1}"));

        DocumentTextExtractionService.ExtractionResult result = fixture.service().extract(DOCUMENT_ID);

        assertThat(result.source()).isEqualTo(DocumentTextExtractionService.Source.OCR);
        assertThat(fixture.ocr.calls).isEqualTo(1);
        assertThat(fixture.accounting.events).hasSize(1);
        assertThat(fixture.accounting.events.getFirst().documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(fixture.accounting.events.getFirst().usageData().value()).isEqualTo("{\"pages\":1}");
    }

    @Test
    void failedDocumentCannotReachEitherExtractor() {
        Fixture fixture = new Fixture(new DirectTextExtractorPort.ExtractionResult("text", 1));
        fixture.documents.document = new Document(DOCUMENT_ID, USER_ID, "private/trusted/documents/1", "x.pdf",
                "application/pdf", 20, DocumentStatus.FAILED, OffsetDateTime.now(), OffsetDateTime.now());

        assertThatThrownBy(() -> fixture.service().extract(DOCUMENT_ID))
                .isInstanceOf(DocumentTextExtractionService.UnsupportedDocumentException.class);
        assertThat(fixture.storage.reads).isZero();
        assertThat(fixture.ocr.calls).isZero();
    }

    @Test
    void overlongOutputFailsAfterPaidInvocationIsAccounted() {
        Fixture fixture = new Fixture(new DirectTextExtractorPort.ExtractionResult("", 1));
        fixture.limits = new TextExtractionProperties(1000, 10, 10, 5);
        fixture.ocr.result = new OcrTextExtractorPort.OcrResult("01234567890", new JsonValue("{}"));

        assertThatThrownBy(() -> fixture.service().extract(DOCUMENT_ID))
                .isInstanceOfSatisfying(DocumentTextExtractionService.ResourceLimitException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ProcessingErrorCode.RESOURCE_LIMIT_OUTPUT_CHARACTERS));
        assertThat(fixture.accounting.events).hasSize(1);
    }

    @Test
    void inputAndStructuralExtractionLimitsHaveStableClassifications() {
        Fixture oversizedDocument = new Fixture(new DirectTextExtractorPort.ExtractionResult("text", 1));
        oversizedDocument.documents.document = new Document(DOCUMENT_ID, USER_ID, "private/trusted/documents/1", "x.pdf",
                "application/pdf", 1001, DocumentStatus.PROCESSING, OffsetDateTime.now(), OffsetDateTime.now());
        assertThatThrownBy(() -> oversizedDocument.service().extract(DOCUMENT_ID))
                .isInstanceOfSatisfying(DocumentTextExtractionService.ResourceLimitException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ProcessingErrorCode.RESOURCE_LIMIT_INPUT_BYTES));

        Fixture emptyContent = new Fixture(new DirectTextExtractorPort.ExtractionResult("text", 1));
        emptyContent.storage.content = new byte[0];
        assertThatThrownBy(() -> emptyContent.service().extract(DOCUMENT_ID))
                .isInstanceOfSatisfying(DocumentTextExtractionService.ResourceLimitException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ProcessingErrorCode.RESOURCE_LIMIT_EMPTY_CONTENT));

        Fixture pages = new Fixture(new DirectTextExtractorPort.ExtractionResult("text", 11));
        assertThatThrownBy(() -> pages.service().extract(DOCUMENT_ID))
                .isInstanceOfSatisfying(DocumentTextExtractionService.ResourceLimitException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ProcessingErrorCode.RESOURCE_LIMIT_PAGE_COUNT));
    }

    @Test
    void retryableOcrFailureIsRetryableAndPaidInvocationIsAccounted() {
        Fixture fixture = new Fixture(new DirectTextExtractorPort.ExtractionResult("", 1));
        fixture.ocr.failure = new OcrTextExtractorPort.RetryableOcrException(new RuntimeException());

        assertThatThrownBy(() -> fixture.service().extract(DOCUMENT_ID))
                .isInstanceOf(RetryableProcessingException.class);
        assertThat(fixture.ocr.calls).isEqualTo(1);
        assertThat(fixture.accounting.events).hasSize(1);
        assertThat(fixture.accounting.events.getFirst().usageData().value()).contains("\"pages\":null");
    }

    @Test
    void permanentOcrFailureIsNotRetryableAndPaidInvocationIsAccounted() {
        Fixture fixture = new Fixture(new DirectTextExtractorPort.ExtractionResult("", 1));
        fixture.ocr.failure = new OcrTextExtractorPort.PermanentOcrException(new RuntimeException());

        assertThatThrownBy(() -> fixture.service().extract(DOCUMENT_ID))
                .isInstanceOf(PermanentProcessingException.class);
        assertThat(fixture.ocr.calls).isEqualTo(1);
        assertThat(fixture.accounting.events).hasSize(1);
        assertThat(fixture.accounting.events.getFirst().usageData().value()).contains("\"pages\":null");
    }

    @Test
    void repeatedOcrAttemptsPublishSeparateUsageEvents() {
        Fixture fixture = new Fixture(new DirectTextExtractorPort.ExtractionResult("", 1));
        fixture.ocr.failure = new OcrTextExtractorPort.RetryableOcrException(new RuntimeException());

        assertThatThrownBy(() -> fixture.service().extract(DOCUMENT_ID))
                .isInstanceOf(RetryableProcessingException.class);
        assertThatThrownBy(() -> fixture.service().extract(DOCUMENT_ID))
                .isInstanceOf(RetryableProcessingException.class);

        assertThat(fixture.accounting.events).hasSize(2);
        assertThat(fixture.accounting.events.get(0).usageEventId())
                .isNotEqualTo(fixture.accounting.events.get(1).usageEventId());
    }

    @Test
    void providerPreparationFailureDoesNotPublishUsage() {
        Fixture fixture = new Fixture(new DirectTextExtractorPort.ExtractionResult("", 1));
        fixture.ocr.failure = new OcrTextExtractorPort.PermanentOcrException(new RuntimeException());
        fixture.ocr.recordInvocation = false;

        assertThatThrownBy(() -> fixture.service().extract(DOCUMENT_ID))
                .isInstanceOf(PermanentProcessingException.class);
        assertThat(fixture.accounting.events).isEmpty();
    }

    private static final class Fixture {
        private final Documents documents = new Documents();
        private final Storage storage = new Storage();
        private final DirectTextExtractorPort.ExtractionResult directResult;
        private final Ocr ocr = new Ocr();
        private final Accounting accounting = new Accounting();
        private TextExtractionProperties limits = LIMITS;

        Fixture(DirectTextExtractorPort.ExtractionResult directResult) { this.directResult = directResult; }
        DocumentTextExtractionService service() {
            return new DocumentTextExtractionService(documents, storage, ignored -> directResult, ocr, accounting, limits);
        }
    }

    private static final class Documents implements RepositoryPorts.DocumentRepository {
        private Document document = new Document(DOCUMENT_ID, USER_ID, "private/trusted/documents/1", "x.pdf",
                "application/pdf", 20, DocumentStatus.PROCESSING, OffsetDateTime.now(), OffsetDateTime.now());
        @Override public Optional<Document> findById(UUID id) { return Optional.of(document); }
        @Override public Optional<Document> findByIdForUpdate(UUID id) { return Optional.of(document); }
        @Override public void save(Document document) { this.document = document; }
        @Override public void markProcessingSucceeded(UUID id) { }
        @Override public int markProcessingFailed(UUID id) { return 0; }
        @Override public void deleteById(UUID id) { }
    }
    private static final class Storage implements UploadObjectStoragePort {
        private int reads;
        private byte[] content = new byte[20];
        @Override public ObjectMetadata head(String key) { return metadata(key); }
        @Override public String trustedKeyFor(UUID id) { return "private/trusted/documents/" + id; }
        @Override public void promoteToTrusted(String source, String trusted) { }
        @Override public ObjectMetadata headTrusted(String key) { return metadata(key); }
        @Override public byte[] readPrefix(String key, int max) { return new byte[20]; }
        @Override public byte[] readTrusted(String key, int max) { reads++; return content; }
        @Override public void deleteQuarantineObject(String key) { }
        private ObjectMetadata metadata(String key) { return new ObjectMetadata(key, 20, "application/pdf", java.util.Map.of()); }
    }
    private static final class Ocr implements OcrTextExtractorPort {
        private int calls;
        private RuntimeException failure;
        private boolean recordInvocation = true;
        private OcrResult result = new OcrResult("ocr", new JsonValue("{}"));
        @Override public OcrResult extract(byte[] document, OcrAttemptRecorder attemptRecorder) {
            calls++;
            if (recordInvocation) attemptRecorder.recordInvocation();
            if (failure != null) throw failure;
            return result;
        }
    }
    private static final class Accounting implements AccountingPort {
        private final List<UsageEvent> events = new ArrayList<>();
        @Override public void publish(UsageEvent event) { events.add(event); }
    }
}
