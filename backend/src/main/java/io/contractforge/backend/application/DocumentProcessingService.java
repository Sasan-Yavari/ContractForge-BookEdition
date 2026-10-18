package io.contractforge.backend.application;

import io.contractforge.backend.application.port.RepositoryPorts.ContractRepository;
import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Profile;

/** Coordinates the approved atomic Document-success and Contract-creation operation. */
@Service
@Profile("!test")
public class DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final ContractRepository contractRepository;

    public DocumentProcessingService(DocumentRepository documentRepository,
                                     ContractRepository contractRepository) {
        this.documentRepository = documentRepository;
        this.contractRepository = contractRepository;
    }

    @Transactional
    public void completeSuccessfully(UUID documentId, ContractAggregate contract) {
        if (!documentId.equals(contract.contract().documentId())) {
            throw new IllegalArgumentException("Contract must belong to the processed document");
        }
        documentRepository.markProcessingSucceeded(documentId);
        contractRepository.save(contract);
    }
}
