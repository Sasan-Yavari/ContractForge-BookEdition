package io.contractforge.backend.application.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.DocumentProcessingService;
import io.contractforge.backend.application.port.ProcessingPublisherPort.ProcessingMessage;
import io.contractforge.backend.application.port.RepositoryPorts;
import io.contractforge.backend.domain.model.PersistenceModels;
import io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate;
import io.contractforge.backend.domain.model.PersistenceModels.Document;
import io.contractforge.backend.domain.model.PersistenceModels.ProcessingAttempt;
import io.contractforge.backend.domain.model.ProcessingAttemptStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcessingOrchestrationTests {
    private static final UUID DOCUMENT_ID = UUID.randomUUID();

    @Test
    void duplicateDeliveryWhileRunningDoesNotCreateAnotherAttempt() {
        Fixture fixture = new Fixture();
        ProcessingAttemptLifecycleService service = fixture.lifecycle();

        ProcessingAttempt first = service.begin(DOCUMENT_ID);
        ProcessingAttempt duplicate = service.begin(DOCUMENT_ID);

        assertThat(first.attemptNumber()).isEqualTo(1);
        assertThat(duplicate).isNull();
        assertThat(fixture.attempts.rows).hasSize(1);
    }

    @Test
    void activeAttemptDefersDuplicateDeliveryUntilStaleRecoveryCanRun() {
        Fixture fixture = new Fixture();
        fixture.lifecycle().begin(DOCUMENT_ID);

        ProcessingOrchestratorService.Outcome outcome = fixture.service().consume(new ProcessingMessage(DOCUMENT_ID));

        assertThat(outcome).isEqualTo(ProcessingOrchestratorService.Outcome.IN_PROGRESS);
        assertThat(fixture.attempts.rows).hasSize(1);
        assertThat(fixture.attempts.rows.getFirst().status()).isEqualTo(ProcessingAttemptStatus.RUNNING);
    }

    @Test
    void failedAttemptIsFinalizedAndLaterApplicationRetryGetsNextNumber() {
        Fixture fixture = new Fixture();
        ProcessingAttemptLifecycleService service = fixture.lifecycle();

        ProcessingAttempt first = service.begin(DOCUMENT_ID);
        service.complete(first.id(), ProcessingAttemptStatus.FAILED, "RetryableProcessingException");
        ProcessingAttempt retry = service.begin(DOCUMENT_ID);

        assertThat(fixture.attempts.rows).extracting(ProcessingAttempt::status)
                .containsExactly(ProcessingAttemptStatus.FAILED, ProcessingAttemptStatus.RUNNING);
        assertThat(retry.attemptNumber()).isEqualTo(2);
        assertThat(fixture.attempts.rows.get(0).errorCode()).doesNotContain("document contents");
    }

    @Test
    void malformedMessagesFailClosedBeforeAnyRepositoryWork() {
        assertThatThrownBy(() -> ProcessingMessageCodec.decode("{\"documentId\":\"not-a-uuid\"}"))
                .isInstanceOf(ProcessingMessageCodec.MalformedProcessingMessageException.class);
    }

    @Test
    void staleRunningAttemptIsRecoveredForTheNextDelivery() {
        Fixture fixture = new Fixture();
        fixture.attempts.save(new ProcessingAttempt(UUID.randomUUID(), DOCUMENT_ID, 1, "RUNNING",
                OffsetDateTime.now().minusHours(2), null, null));

        ProcessingAttempt next = fixture.lifecycle().begin(DOCUMENT_ID);

        assertThat(next.attemptNumber()).isEqualTo(2);
        assertThat(fixture.attempts.rows.get(0).status()).isEqualTo(ProcessingAttemptStatus.FAILED);
        assertThat(fixture.attempts.rows.get(0).errorCode()).isEqualTo("STALE_ATTEMPT_RECOVERED");
    }

    @Test
    void recoveredAttemptCannotCompleteAfterItsOwnershipIsLost() {
        Fixture fixture = new Fixture();
        ProcessingAttempt stale = fixture.attempts.saveAndReturn(new ProcessingAttempt(UUID.randomUUID(), DOCUMENT_ID, 1,
                "RUNNING", OffsetDateTime.now().minusHours(2), null, null));
        fixture.lifecycle().begin(DOCUMENT_ID);

        assertThatThrownBy(() -> fixture.lifecycle().completeSuccessfully(stale.id(), DOCUMENT_ID,
                new ContractAggregate(null, List.of(), null)))
                .isInstanceOf(ProcessingAttemptLifecycleService.StaleAttemptCompletionException.class);
    }

    @Test
    void successfulDeliveryFinalizesTheApplicationAttempt() {
        Fixture fixture = new Fixture();

        fixture.service().consume(new ProcessingMessage(DOCUMENT_ID));

        assertThat(fixture.attempts.rows).extracting(ProcessingAttempt::status).containsExactly(ProcessingAttemptStatus.SUCCEEDED);
    }

    @Test
    void applicationFailureFinalizesTheAttemptAndIsRedeliverable() {
        Fixture fixture = new Fixture();
        ProcessingOrchestratorService service = fixture.service(document -> {
            throw new RetryableProcessingException();
        });

        assertThatThrownBy(() -> service.consume(new ProcessingMessage(DOCUMENT_ID)))
                .isInstanceOf(RetryableProcessingException.class);
        assertThat(fixture.attempts.rows.get(0).status()).isEqualTo(ProcessingAttemptStatus.FAILED);
        assertThat(fixture.attempts.rows.get(0).errorCode())
                .isEqualTo(ProcessingErrorCode.RETRYABLE_PROCESSING_FAILURE);
    }

    @Test
    void unclassifiedFailureUsesStablePrivateCodeWithoutPersistingImplementationExceptionName() {
        Fixture fixture = new Fixture();
        ProcessingOrchestratorService service = fixture.service(document -> {
            throw new IllegalStateException("private infrastructure detail");
        });

        assertThatThrownBy(() -> service.consume(new ProcessingMessage(DOCUMENT_ID)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fixture.attempts.rows.get(0).errorCode())
                .isEqualTo(ProcessingErrorCode.UNCLASSIFIED_PROCESSING_FAILURE);
        assertThat(fixture.attempts.rows.get(0).errorCode()).doesNotContain("infrastructure", "IllegalStateException");
    }

    @Test
    void permanentResourceLimitUsesStableAttemptErrorCode() {
        Fixture fixture = new Fixture();
        ProcessingOrchestratorService service = fixture.service(document -> {
            throw new io.contractforge.backend.application.extraction.DocumentTextExtractionService.ResourceLimitException(
                    ProcessingErrorCode.RESOURCE_LIMIT_OUTPUT_CHARACTERS);
        });

        assertThatThrownBy(() -> service.consume(new ProcessingMessage(DOCUMENT_ID)))
                .isInstanceOf(PermanentProcessingException.class);
        assertThat(fixture.attempts.rows.get(0).errorCode())
                .isEqualTo(ProcessingErrorCode.RESOURCE_LIMIT_OUTPUT_CHARACTERS);
    }

    private static final class Fixture {
        private final Documents documents = new Documents();
        private final Attempts attempts = new Attempts();

        private ProcessingAttemptLifecycleService lifecycle() {
            DocumentProcessingService completion = new DocumentProcessingService(null, null) {
                @Override
                public void completeSuccessfully(UUID documentId, ContractAggregate aggregate) {
                    // Lifecycle tests focus on the attempt boundary.
                }
            };
            return new ProcessingAttemptLifecycleService(documents, attempts, completion);
        }

        private ProcessingOrchestratorService service() {
            return service(document -> new ContractAggregate(null, List.of(), null));
        }

        private ProcessingOrchestratorService service(ProcessingWorkPort work) {
            return new ProcessingOrchestratorService(documents, lifecycle(),
                    work, new DocumentProcessingService(null, null) {
                        @Override
                        public void completeSuccessfully(UUID documentId, ContractAggregate aggregate) {
                            // Lifecycle tests focus on the attempt boundary.
                        }
                    });
        }
    }

    private static final class Documents implements RepositoryPorts.DocumentRepository {
        private final Document document = new Document(DOCUMENT_ID, UUID.randomUUID(), "private/trusted/document",
                "document.pdf", "application/pdf", 1, "PROCESSING", OffsetDateTime.now(), OffsetDateTime.now());

        @Override public Optional<Document> findById(UUID id) { return id.equals(DOCUMENT_ID) ? Optional.of(document) : Optional.empty(); }
        @Override public Optional<Document> findByIdForUpdate(UUID id) { return findById(id); }
        @Override public void save(Document value) { }
        @Override public void markProcessingSucceeded(UUID id) { }
        @Override public int markProcessingFailed(UUID id) { return 0; }
        @Override public void deleteById(UUID id) { }
    }

    private static final class Attempts implements RepositoryPorts.ProcessingAttemptRepository {
        private final List<ProcessingAttempt> rows = new ArrayList<>();

        @Override public synchronized void save(ProcessingAttempt attempt) { rows.add(attempt); }
        private synchronized ProcessingAttempt saveAndReturn(ProcessingAttempt attempt) { save(attempt); return attempt; }
        @Override public synchronized Optional<ProcessingAttempt> findById(UUID id) { return rows.stream().filter(a -> a.id().equals(id)).findFirst(); }
        @Override public synchronized Optional<ProcessingAttempt> findLatestByDocumentId(UUID id) { return rows.stream().filter(a -> a.documentId().equals(id)).reduce((a, b) -> b); }
        @Override public synchronized Optional<ProcessingAttempt> findRunningByDocumentId(UUID id) { return rows.stream().filter(a -> a.documentId().equals(id) && a.status() == ProcessingAttemptStatus.RUNNING).findFirst(); }
        @Override public synchronized int finalize(UUID id, ProcessingAttemptStatus status, String errorCode) {
            for (int i = 0; i < rows.size(); i++) {
                ProcessingAttempt row = rows.get(i);
                if (row.id().equals(id) && row.status() == ProcessingAttemptStatus.RUNNING) {
                    rows.set(i, new ProcessingAttempt(row.id(), row.documentId(), row.attemptNumber(), status,
                            row.startedAt(), OffsetDateTime.now(), errorCode));
                    return 1;
                }
            }
            return 0;
        }
        @Override public synchronized int finalizeRunningByDocumentId(UUID id, String errorCode) {
            return rows.stream().filter(a -> a.documentId().equals(id) && a.status() == ProcessingAttemptStatus.RUNNING)
                    .map(a -> finalize(a.id(), ProcessingAttemptStatus.FAILED, errorCode)).mapToInt(Integer::intValue).sum();
        }
    }
}
