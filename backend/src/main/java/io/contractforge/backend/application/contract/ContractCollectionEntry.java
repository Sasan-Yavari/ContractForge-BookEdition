package io.contractforge.backend.application.contract;

import io.contractforge.backend.domain.model.ContractPricingModel;
import io.contractforge.backend.domain.model.ContractStatus;
import io.contractforge.backend.domain.model.ContractType;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.UploadStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Persisted facts needed to project one row into the authenticated owner's collection. */
public record ContractCollectionEntry(UUID id, ContractType type, OffsetDateTime createdAt,
                                      String sourceFilename, UploadStatus uploadStatus,
                                      DocumentStatus documentStatus, ContractStatus contractStatus,
                                      String provider, ContractPricingModel pricingModel,
                                      LocalDate endDate, boolean needsAttention) { }
