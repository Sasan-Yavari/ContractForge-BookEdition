package io.contractforge.backend.api.security;

/** Profile attributes returned by Cognito's authenticated UserInfo endpoint. */
record CognitoUserInfo(String subject, String email, boolean emailVerified) {
}
