package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.CognitoAccountAdministrationPort;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDisableUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

@Component
@Profile("!local & !test")
final class CognitoAccountAdministrationAdapter implements CognitoAccountAdministrationPort {
    private static final String COGNITO_PROVIDER = "cognito";

    private final CognitoIdentityProviderClient cognito;
    private final String userPoolId;

    CognitoAccountAdministrationAdapter(
            @Value("${CONTRACTFORGE_COGNITO_USER_POOL_ID:}") String userPoolId,
            @Value("${AWS_REGION:eu-west-1}") String region) {
        this.userPoolId = userPoolId;
        this.cognito = CognitoIdentityProviderClient.builder().region(Region.of(region))
                .overrideConfiguration(configuration -> configuration.apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(8)))
                .build();
    }

    @Override
    public void disable(String provider, String providerSubject) {
        validate(provider, providerSubject);
        try {
            cognito.adminDisableUser(AdminDisableUserRequest.builder().userPoolId(userPoolId)
                    .username(providerSubject).build());
        } catch (UserNotFoundException alreadyAbsent) {
            // A manually removed Cognito profile is already unable to authenticate.
        }
    }

    @Override
    public void delete(String provider, String providerSubject) {
        validate(provider, providerSubject);
        try {
            cognito.adminDeleteUser(AdminDeleteUserRequest.builder().userPoolId(userPoolId)
                    .username(providerSubject).build());
        } catch (UserNotFoundException alreadyAbsent) {
            // Retry after an ambiguous successful response is safe.
        }
    }

    private void validate(String provider, String providerSubject) {
        if (userPoolId == null || userPoolId.isBlank()) {
            throw new IllegalStateException("Cognito account administration is not configured");
        }
        if (!COGNITO_PROVIDER.equals(provider) || providerSubject == null || providerSubject.isBlank()) {
            throw new IllegalArgumentException("Cognito identity is incomplete");
        }
    }
}
