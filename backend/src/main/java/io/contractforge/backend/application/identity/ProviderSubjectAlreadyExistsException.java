package io.contractforge.backend.application.identity;

/** Signals only the approved provider/provider-subject uniqueness race. */
public class ProviderSubjectAlreadyExistsException extends RuntimeException {

    public ProviderSubjectAlreadyExistsException(Throwable cause) {
        super("The external provider subject already exists", cause);
    }
}
