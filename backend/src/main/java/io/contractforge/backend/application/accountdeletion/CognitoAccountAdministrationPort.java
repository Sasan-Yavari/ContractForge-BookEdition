package io.contractforge.backend.application.accountdeletion;

/** Narrow administrative boundary for the authenticated Cognito subject. */
public interface CognitoAccountAdministrationPort {
    void disable(String provider, String providerSubject);

    void delete(String provider, String providerSubject);
}
