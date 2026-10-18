package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.CognitoAccountAdministrationPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The local-only auth adapter has no external Cognito account to administer. */
@Component
@Profile("local")
final class LocalCognitoAccountAdministrationAdapter implements CognitoAccountAdministrationPort {
    @Override
    public void disable(String provider, String providerSubject) { }

    @Override
    public void delete(String provider, String providerSubject) { }
}
