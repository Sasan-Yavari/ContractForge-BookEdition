package io.contractforge.backend.application.extraction;

/** Provider-neutral boundary for deterministic local text extraction. */
public interface DirectTextExtractorPort {
    ExtractionResult extract(byte[] document);

    class MalformedDocumentException extends RuntimeException {
        public MalformedDocumentException(Throwable cause) { super(cause); }
    }

    record ExtractionResult(String text, int pages) {
        public ExtractionResult {
            if (text == null || pages < 0) throw new IllegalArgumentException("Invalid direct extraction result");
        }
    }
}
