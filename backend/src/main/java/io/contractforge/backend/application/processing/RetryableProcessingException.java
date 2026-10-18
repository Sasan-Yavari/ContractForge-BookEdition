package io.contractforge.backend.application.processing;

/** Signals a transient application failure; the queue runtime must leave the message for redelivery. */
public class RetryableProcessingException extends RuntimeException {
    public RetryableProcessingException() { }
    public RetryableProcessingException(Throwable cause) { super(cause); }
}
