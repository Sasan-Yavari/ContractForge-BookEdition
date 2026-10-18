package io.contractforge.backend.application.identity;

/** The external identity is valid, but its application account cannot proceed. */
public class ApplicationAccessDeniedException extends RuntimeException {

    public ApplicationAccessDeniedException() {
        super("Application user is not active");
    }
}
