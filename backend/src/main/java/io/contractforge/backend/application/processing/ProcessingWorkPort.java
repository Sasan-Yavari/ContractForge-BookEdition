package io.contractforge.backend.application.processing;

import io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate;
import io.contractforge.backend.domain.model.PersistenceModels.Document;

/** Provider-independent seam for the later OCR/extraction implementation. */
public interface ProcessingWorkPort {
    ContractAggregate process(Document document);
}
