package io.contractforge.backend.application.processing;

/** Stable, non-sensitive processing-attempt error codes. */
public final class ProcessingErrorCode {
    public static final String STALE_ATTEMPT_RECOVERED = "STALE_ATTEMPT_RECOVERED";
    public static final String PROCESSING_DLQ = "PROCESSING_DLQ";
    public static final String RESOURCE_LIMIT_INPUT_BYTES = "RESOURCE_LIMIT_INPUT_BYTES";
    public static final String RESOURCE_LIMIT_EMPTY_CONTENT = "RESOURCE_LIMIT_EMPTY_CONTENT";
    public static final String RESOURCE_LIMIT_PAGE_COUNT = "RESOURCE_LIMIT_PAGE_COUNT";
    public static final String RESOURCE_LIMIT_OUTPUT_CHARACTERS = "RESOURCE_LIMIT_OUTPUT_CHARACTERS";
    public static final String RESOURCE_LIMIT_LLM_INPUT_CHARACTERS = "RESOURCE_LIMIT_LLM_INPUT_CHARACTERS";
    public static final String RETRYABLE_PROCESSING_FAILURE = "RETRYABLE_PROCESSING_FAILURE";
    public static final String MALFORMED_DOCUMENT = "MALFORMED_DOCUMENT";
    public static final String UNCLASSIFIED_PROCESSING_FAILURE = "UNCLASSIFIED_PROCESSING_FAILURE";
    public static final String LEGACY_RETRYABLE_PROCESSING_EXCEPTION = "RetryableProcessingException";
    public static final String LEGACY_MALFORMED_DOCUMENT_EXCEPTION = "MalformedDocumentException";

    private ProcessingErrorCode() { }
}
