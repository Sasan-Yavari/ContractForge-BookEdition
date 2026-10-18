package io.contractforge.backend.application.extraction;

import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;

/** Provider-neutral OCR boundary; provider SDK types remain outside application code. */
public interface OcrTextExtractorPort {
    OcrResult extract(byte[] document, OcrAttemptRecorder attemptRecorder);

    @FunctionalInterface
    interface OcrAttemptRecorder {
        void recordInvocation();
    }

    record OcrResult(String text, JsonValue usageData) {
        public OcrResult {
            if (text == null || usageData == null) throw new IllegalArgumentException("Invalid OCR result");
        }
    }

    class RetryableOcrException extends RuntimeException {
        public RetryableOcrException(Throwable cause) { super(cause); }
    }

    class PermanentOcrException extends RuntimeException {
        public PermanentOcrException(Throwable cause) { super(cause); }
    }
}
