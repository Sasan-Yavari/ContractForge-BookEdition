package io.contractforge.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.contractforge.backend.application.accountdeletion.AccountDeletionService;
import io.contractforge.backend.application.accountdeletion.AccountDeletionWorker;
import io.contractforge.backend.application.accountdeletion.AccountDeletionDisablePendingException;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

class AccountDeletionControllerTests {
    @Test
    void acceptsOnlyAfterDurableRequestThenImmediatelyAttemptsCognitoDisable() {
        AccountDeletionService deletions = mock(AccountDeletionService.class);
        AccountDeletionWorker worker = mock(AccountDeletionWorker.class);
        UUID deletionId = UUID.randomUUID();
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "cognito", "subject-123", "owner@example.test", true);
        when(deletions.request(principal)).thenReturn(deletionId);
        AccountDeletionController controller = new AccountDeletionController(deletions, worker);

        assertThat(controller.delete(principal).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        InOrder order = inOrder(deletions, worker);
        order.verify(deletions).request(principal);
        order.verify(worker).disableCognitoImmediately(deletionId);
    }

    @Test
    void doesNotReturnAcceptedWhenImmediateCognitoDisableIsStillPending() {
        AccountDeletionService deletions = mock(AccountDeletionService.class);
        AccountDeletionWorker worker = mock(AccountDeletionWorker.class);
        UUID deletionId = UUID.randomUUID();
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "cognito", "subject-123", "owner@example.test", true);
        when(deletions.request(principal)).thenReturn(deletionId);
        org.mockito.Mockito.doThrow(new AccountDeletionDisablePendingException(5))
                .when(worker).disableCognitoImmediately(deletionId);
        AccountDeletionController controller = new AccountDeletionController(deletions, worker);

        assertThatThrownBy(() -> controller.delete(principal))
                .isInstanceOf(AccountDeletionDisablePendingException.class);
    }
}
