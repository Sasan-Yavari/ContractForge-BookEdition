package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.port.RepositoryPorts;
import io.contractforge.backend.application.port.UploadObjectStoragePort;
import io.contractforge.backend.application.port.UploadObjectStoragePort.ObjectMetadata;
import io.contractforge.backend.application.upload.UploadFinalizationService;
import io.contractforge.backend.application.upload.UploadIngestionService;
import io.contractforge.backend.application.document.DocumentStatusService;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.domain.model.PersistenceModels.Document;
import io.contractforge.backend.domain.model.PersistenceModels.Upload;
import io.contractforge.backend.domain.model.ContractType;
import io.contractforge.backend.domain.model.UploadStatus;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

@ActiveProfiles("integration")
@Import(UploadFinalizationIntegrationTests.StorageConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UploadFinalizationIntegrationTests extends PostgresIntegrationTest {
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID UPLOAD_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final String SOURCE_KEY = "quarantine/incoming/" + UPLOAD_ID;
    private static final String TRUSTED_KEY = "private/trusted/documents/" + UPLOAD_ID;

    @Autowired private RepositoryPorts.UploadRepository uploads;
    @Autowired private RepositoryPorts.DocumentRepository documents;
    @Autowired private UploadFinalizationService finalization;
    @Autowired private DocumentStatusService documentStatuses;
    @Autowired private FakeStorage storage;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM documents WHERE id = ?", UPLOAD_ID);
        jdbc.update("DELETE FROM uploads WHERE id = ?", UPLOAD_ID);
        storage.reset();
    }

    @Test
    void promotesFinalizesAuthoritativeDocumentAndReplaysWithoutRecopying() {
        uploads.save(upload(UploadStatus.SCANNED, "قرارداد برق.pdf"));

        Upload completed = finalization.finalizeUpload(UPLOAD_ID, SOURCE_KEY);
        Document document = documents.findById(UPLOAD_ID).orElseThrow();

        assertThat(completed.status()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(document.id()).isEqualTo(UPLOAD_ID);
        assertThat(document.userId()).isEqualTo(USER_ID);
        assertThat(document.filename()).isEqualTo("قرارداد برق.pdf");
        assertThat(document.contractType()).isEqualTo(ContractType.ELECTRICITY);
        var status = documentStatuses.status(new AuthenticatedApplicationUser(USER_ID), UPLOAD_ID);
        assertThat(status.originalFilename()).isEqualTo("قرارداد برق.pdf");
        assertThat(status.contractType()).isEqualTo(ContractType.ELECTRICITY);
        assertThat(status.failureCategory()).isNull();
        assertThat(document.storageKey()).isEqualTo(TRUSTED_KEY);
        assertThat(document.mimeType()).isEqualTo("application/pdf");
        assertThat(document.size()).isEqualTo(42);
        assertThat(uploads.findById(UPLOAD_ID).orElseThrow().status()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(uploads.findById(UPLOAD_ID).orElseThrow().contractType())
                .isEqualTo(ContractType.ELECTRICITY);

        finalization.finalizeUpload(UPLOAD_ID, SOURCE_KEY);
        assertThat(storage.copyCalls).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id = ?", Integer.class, UPLOAD_ID)).isEqualTo(1);
    }

    @Test
    void mismatchedTrustedObjectLeavesScannedUploadWithoutDocument() {
        uploads.save(upload(UploadStatus.SCANNED, "contract.pdf"));
        storage.trusted = new ObjectMetadata(TRUSTED_KEY, 99, "application/pdf", new HashMap<>());

        assertThatThrownBy(() -> finalization.finalizeUpload(UPLOAD_ID, SOURCE_KEY))
                .isInstanceOf(UploadIngestionService.RetryableIngestionException.class);
        assertThat(uploads.findById(UPLOAD_ID).orElseThrow().status()).isEqualTo(UploadStatus.SCANNED);
        assertThat(documents.findById(UPLOAD_ID)).isEmpty();
        assertThat(storage.copyCalls).isZero();
    }

    @Test
    void concurrentFinalizationConvergesToOneDocumentAndCompletedUpload() throws Exception {
        uploads.save(upload(UploadStatus.SCANNED, "contract.pdf"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> finalizeAfter(start));
            var second = executor.submit(() -> finalizeAfter(start));
            start.countDown();

            assertThat(first.get().status()).isEqualTo(UploadStatus.COMPLETED);
            assertThat(second.get().status()).isEqualTo(UploadStatus.COMPLETED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id = ?", Integer.class, UPLOAD_ID)).isEqualTo(1);
            assertThat(uploads.findById(UPLOAD_ID).orElseThrow().status()).isEqualTo(UploadStatus.COMPLETED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void documentWriteAndUploadCompletionFailureRollBackTogether() {
        uploads.save(upload(UploadStatus.PROMOTED, "contract.pdf"));
        storage.trusted = new ObjectMetadata(TRUSTED_KEY, 42, "application/pdf", new HashMap<>());
        UploadFinalizationService failing = new UploadFinalizationService(
                new FailingCompletionUploadRepository(uploads), documents, storage);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                failing.finalizeUpload(UPLOAD_ID, SOURCE_KEY)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(documents.findById(UPLOAD_ID)).isEmpty();
        assertThat(uploads.findById(UPLOAD_ID).orElseThrow().status()).isEqualTo(UploadStatus.PROMOTED);
    }

    @Test
    void trustedHeadServiceFailureDoesNotTriggerASecondCopy() {
        uploads.save(upload(UploadStatus.SCANNED, "contract.pdf"));
        storage.failHeadTrusted = true;

        assertThatThrownBy(() -> finalization.finalizeUpload(UPLOAD_ID, SOURCE_KEY))
                .isInstanceOf(IllegalStateException.class);
        assertThat(storage.copyCalls).isZero();
        assertThat(uploads.findById(UPLOAD_ID).orElseThrow().status()).isEqualTo(UploadStatus.SCANNED);
    }

    private Upload finalizeAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        return finalization.finalizeUpload(UPLOAD_ID, SOURCE_KEY);
    }

    private Upload upload(UploadStatus status, String filename) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Upload(UPLOAD_ID, USER_ID, status, SOURCE_KEY, filename,
                ContractType.ELECTRICITY, now.plusMinutes(15), now);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {
        @Bean
        @Primary
        FakeStorage uploadObjectStoragePort() { return new FakeStorage(); }
    }

    static final class FakeStorage implements UploadObjectStoragePort {
        private final ObjectMetadata source = new ObjectMetadata(SOURCE_KEY, 42, "application/pdf", new HashMap<>());
        private ObjectMetadata trusted;
        private int copyCalls;
        private boolean failHeadTrusted;

        @Override public ObjectMetadata head(String objectKey) { return source; }
        @Override public String trustedKeyFor(UUID uploadId) { return TRUSTED_KEY; }
        @Override public synchronized void promoteToTrusted(String sourceKey, String trustedKey) {
            copyCalls++;
            trusted = new ObjectMetadata(trustedKey, 42, "application/pdf", new HashMap<>());
        }
        @Override public synchronized ObjectMetadata headTrusted(String objectKey) {
            if (failHeadTrusted) throw new IllegalStateException("storage unavailable");
            if (trusted == null) throw new UploadObjectStoragePort.TrustedObjectNotFoundException(
                    new IllegalStateException("missing trusted object"));
            return trusted;
        }
        @Override public byte[] readPrefix(String objectKey, int maximumBytes) { return new byte[0]; }
        @Override public void deleteQuarantineObject(String objectKey) { }
        void reset() { trusted = null; copyCalls = 0; failHeadTrusted = false; }
    }

    private static final class FailingCompletionUploadRepository implements RepositoryPorts.UploadRepository {
        private final RepositoryPorts.UploadRepository delegate;

        private FailingCompletionUploadRepository(RepositoryPorts.UploadRepository delegate) {
            this.delegate = delegate;
        }

        @Override public void save(Upload upload) { delegate.save(upload); }
        @Override public java.util.Optional<Upload> findById(UUID id) { return delegate.findById(id); }
        @Override public java.util.Optional<Upload> findByIdForUpdate(UUID id) { return delegate.findByIdForUpdate(id); }
        @Override public int markUploaded(UUID id, String key) { return delegate.markUploaded(id, key); }
        @Override public int markScanned(UUID id) { return delegate.markScanned(id); }
        @Override public int markPromoted(UUID id) { return delegate.markPromoted(id); }
        @Override public int markCompleted(UUID id) { return 0; }
        @Override public int markRejected(UUID id, String key) { return delegate.markRejected(id, key); }
    }
}
