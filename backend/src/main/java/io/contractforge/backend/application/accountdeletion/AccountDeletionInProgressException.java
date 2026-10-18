package io.contractforge.backend.application.accountdeletion;

/** The account fence won a race with an application or asynchronous write. */
public final class AccountDeletionInProgressException extends RuntimeException { }
