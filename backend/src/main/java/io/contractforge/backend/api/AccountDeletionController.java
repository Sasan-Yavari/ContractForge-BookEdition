package io.contractforge.backend.api;

import io.contractforge.backend.application.accountdeletion.AccountDeletionService;
import io.contractforge.backend.application.accountdeletion.AccountDeletionWorker;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/account")
@Profile("!test")
public final class AccountDeletionController {
    private final AccountDeletionService deletions;
    private final AccountDeletionWorker worker;

    public AccountDeletionController(AccountDeletionService deletions, AccountDeletionWorker worker) {
        this.deletions = deletions;
        this.worker = worker;
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        var deletionId = deletions.request(principal);
        worker.disableCognitoImmediately(deletionId);
        return ResponseEntity.accepted().build();
    }
}
