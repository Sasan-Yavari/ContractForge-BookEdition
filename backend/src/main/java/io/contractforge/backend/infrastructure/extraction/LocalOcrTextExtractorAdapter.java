package io.contractforge.backend.infrastructure.extraction;

import io.contractforge.backend.application.extraction.OcrTextExtractorPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Local OCR boundary; scanned-document OCR is intentionally unsupported without Textract. */
@Component
@Profile("local")
final class LocalOcrTextExtractorAdapter implements OcrTextExtractorPort {
    @Override
    public OcrResult extract(byte[] document, OcrAttemptRecorder attemptRecorder) {
        throw new PermanentOcrException(new UnsupportedOperationException(
                "OCR is not supported by the local development environment"));
    }
}
