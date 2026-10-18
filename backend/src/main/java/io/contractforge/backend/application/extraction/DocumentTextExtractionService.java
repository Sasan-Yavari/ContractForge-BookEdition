package io.contractforge.backend.application.extraction;

import io.contractforge.backend.application.accounting.AccountingPort;
import io.contractforge.backend.application.accounting.CostCalculation;
import io.contractforge.backend.application.accounting.ProviderCostCalculator;
import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.application.port.UploadObjectStoragePort;
import io.contractforge.backend.application.processing.PermanentProcessingException;
import io.contractforge.backend.application.processing.ProcessingErrorCode;
import io.contractforge.backend.application.processing.RetryableProcessingException;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.PersistenceModels.Document;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Chooses bounded local PDF extraction first and OCR only when direct text is insufficient. */
@Service
@Profile("!test")
public class DocumentTextExtractionService {
    private static final String PDF = "application/pdf";
    private final DocumentRepository documents;
    private final UploadObjectStoragePort storage;
    private final DirectTextExtractorPort direct;
    private final OcrTextExtractorPort ocr;
    private final AccountingPort accounting;
    private final TextExtractionProperties properties;
    private final ProviderCostCalculator costCalculator;
    private final String textractRegion;

    @Autowired
    public DocumentTextExtractionService(DocumentRepository documents, UploadObjectStoragePort storage,
                                         DirectTextExtractorPort direct, OcrTextExtractorPort ocr,
                                         AccountingPort accounting, TextExtractionProperties properties,
                                         ProviderCostCalculator costCalculator,
                                         @Value("${contractforge.extraction.textract.region:eu-west-1}") String textractRegion) {
        this.documents = documents; this.storage = storage; this.direct = direct; this.ocr = ocr;
        this.accounting = accounting; this.properties = properties; this.costCalculator = costCalculator;
        this.textractRegion = textractRegion;
    }

    public DocumentTextExtractionService(DocumentRepository documents, UploadObjectStoragePort storage,
                                         DirectTextExtractorPort direct, OcrTextExtractorPort ocr,
                                         AccountingPort accounting, TextExtractionProperties properties) {
        this(documents, storage, direct, ocr, accounting, properties,
                (provider, service, dimension, usage, occurredAt) -> CostCalculation.unknown("USD"), "eu-west-1");
    }

    public ExtractionResult extract(UUID documentId) {
        Document document = documents.findById(documentId).orElseThrow(UnsupportedDocumentException::new);
        if (document.status() != DocumentStatus.PROCESSING || !PDF.equalsIgnoreCase(document.mimeType())) {
            throw new UnsupportedDocumentException();
        }
        if (document.size() <= 0 || document.size() > properties.maxInputBytes()) {
            throw new ResourceLimitException(ProcessingErrorCode.RESOURCE_LIMIT_INPUT_BYTES);
        }
        byte[] content = storage.readTrusted(document.storageKey(), Math.toIntExact(properties.maxInputBytes()));
        if (content == null || content.length == 0) {
            throw new ResourceLimitException(ProcessingErrorCode.RESOURCE_LIMIT_EMPTY_CONTENT);
        }
        if (content.length > properties.maxInputBytes()) {
            throw new ResourceLimitException(ProcessingErrorCode.RESOURCE_LIMIT_INPUT_BYTES);
        }
        DirectTextExtractorPort.ExtractionResult directResult;
        try {
            directResult = direct.extract(content);
        } catch (DirectTextExtractorPort.MalformedDocumentException failure) {
            throw new PermanentProcessingException(ProcessingErrorCode.MALFORMED_DOCUMENT);
        }
        if (directResult.pages() > properties.maxPages()) {
            throw new ResourceLimitException(ProcessingErrorCode.RESOURCE_LIMIT_PAGE_COUNT);
        }
        if (directResult.text().length() > properties.maxOutputCharacters()) {
            throw new ResourceLimitException(ProcessingErrorCode.RESOURCE_LIMIT_OUTPUT_CHARACTERS);
        }
        if (directResult.text().strip().length() >= properties.minimumDirectTextCharacters()) {
            return new ExtractionResult(directResult.text(), Source.DIRECT);
        }
        OcrTextExtractorPort.OcrResult ocrResult;
        InvocationState invocation = new InvocationState();
        try {
            ocrResult = ocr.extract(content, invocation::record);
        } catch (OcrTextExtractorPort.RetryableOcrException failure) {
            if (invocation.invoked) publishOcrUsage(document, new JsonValue("{\"pages\":null}"));
            throw new RetryableProcessingException(failure);
        } catch (OcrTextExtractorPort.PermanentOcrException failure) {
            if (invocation.invoked) publishOcrUsage(document, new JsonValue("{\"pages\":null}"));
            throw new PermanentProcessingException(failure);
        }
        publishOcrUsage(document, ocrResult.usageData());
        if (ocrResult.text().length() > properties.maxOutputCharacters()) {
            throw new ResourceLimitException(ProcessingErrorCode.RESOURCE_LIMIT_OUTPUT_CHARACTERS);
        }
        return new ExtractionResult(ocrResult.text(), Source.OCR);
    }

    private void publishOcrUsage(Document document, JsonValue usageData) {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        CostCalculation cost = costCalculator.calculate("aws", "textract-detect-document-text", textractRegion,
                usageData, occurredAt);
        accounting.publish(new AccountingPort.UsageEvent(UUID.randomUUID(), document.userId(), document.id(),
                "OCR", "aws", "textract-detect-document-text", usageData, cost.amount(), cost.currency(),
                occurredAt, cost.status(), cost.pricingVersion()));
    }

    private static final class InvocationState {
        private boolean invoked;
        private void record() { invoked = true; }
    }

    public enum Source { DIRECT, OCR }
    public record ExtractionResult(String text, Source source) { }
    public static class UnsupportedDocumentException extends RuntimeException { }
    public static class ResourceLimitException extends PermanentProcessingException {
        public ResourceLimitException(String errorCode) { super(errorCode); }
    }
}
