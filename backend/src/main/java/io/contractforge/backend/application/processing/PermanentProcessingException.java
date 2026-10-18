package io.contractforge.backend.application.processing;

/** Signals a non-retryable application failure; the queue redrive path owns terminal handling. */
public class PermanentProcessingException extends RuntimeException {
    private final String errorCode;

    public PermanentProcessingException() { errorCode = null; }
    public PermanentProcessingException(Throwable cause) { super(cause); errorCode = null; }
    public PermanentProcessingException(String errorCode) { this.errorCode = errorCode; }

    public String errorCode() { return errorCode; }
}
