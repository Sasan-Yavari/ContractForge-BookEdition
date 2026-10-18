package io.contractforge.backend.application.processing;

import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.application.port.ActiveAccountWritePort;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Terminal handler for exhausted SQS processing work. */
@Service
@Profile("!test")
public class ProcessingDlqService {
    private final DocumentRepository documents;
    private final ProcessingAttemptLifecycleService lifecycle;
    private final ActiveAccountWritePort activeAccounts;

    public ProcessingDlqService(DocumentRepository documents, ProcessingAttemptLifecycleService lifecycle) {
        this(documents, lifecycle, null);
    }

    @Autowired
    public ProcessingDlqService(DocumentRepository documents, ProcessingAttemptLifecycleService lifecycle,
                                ActiveAccountWritePort activeAccounts) {
        this.documents = documents;
        this.lifecycle = lifecycle;
        this.activeAccounts = activeAccounts;
    }

    @Transactional
    public void handle(io.contractforge.backend.application.port.ProcessingPublisherPort.ProcessingMessage message) {
        var document = documents.findByIdForUpdate(message.documentId()).orElse(null);
        if (document == null || activeAccounts != null && !activeAccounts.lockActiveUser(document.userId())) return;
        lifecycle.failRunning(message.documentId(), ProcessingErrorCode.PROCESSING_DLQ);
        documents.markProcessingFailed(message.documentId());
    }
}
