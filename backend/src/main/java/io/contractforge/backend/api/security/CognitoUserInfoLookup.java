package io.contractforge.backend.api.security;

/** Resolves Cognito user attributes using the validated access token. */
@FunctionalInterface
interface CognitoUserInfoLookup {

    CognitoUserInfo lookup(String accessToken);
}
