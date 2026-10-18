package io.contractforge.backend.application.contract;

import io.contractforge.backend.application.port.RepositoryPorts.ContractCollectionRepository;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.domain.model.ContractStatus;
import io.contractforge.backend.domain.model.ContractType;
import io.contractforge.backend.domain.model.ContractPricingModel;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.UploadStatus;
import java.time.LocalDate;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects persisted upload and contract state into a contract-centric owner collection. */
@Service
@Profile("!test")
public class ContractCollectionService {
    private final ContractCollectionRepository collection;
    private final Clock clock;

    public ContractCollectionService(ContractCollectionRepository collection, Clock clock) {
        this.collection = collection;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ContractCollectionItem> list(AuthenticatedApplicationUser user) {
        if (user == null || user.userId() == null) {
            throw new IllegalArgumentException("Authenticated application user is required");
        }
        OffsetDateTime evaluatedAt = OffsetDateTime.now(clock);
        return collection.findByUserId(user.userId(), evaluatedAt).stream()
                .map(ContractCollectionService::toItem)
                .sorted(Comparator.comparingInt((ContractCollectionItem item) -> item.status().priority())
                        .thenComparing(ContractCollectionItem::createdAt, Comparator.reverseOrder())
                        .thenComparing(item -> item.id().toString(), Comparator.reverseOrder()))
                .toList();
    }

    private static ContractCollectionItem toItem(ContractCollectionEntry entry) {
        ContractCollectionStatus status = status(entry);
        boolean confirmed = status == ContractCollectionStatus.CONFIRMED
                || status == ContractCollectionStatus.NEEDS_ATTENTION;
        return new ContractCollectionItem(entry.id(), entry.type(), status,
                confirmed ? null : entry.sourceFilename(), entry.createdAt(),
                confirmed ? entry.provider() : null,
                confirmed ? entry.pricingModel() : null,
                confirmed ? entry.endDate() : null);
    }

    private static ContractCollectionStatus status(ContractCollectionEntry entry) {
        if (entry.documentStatus() != null) {
            return switch (entry.documentStatus()) {
                case PROCESSING -> ContractCollectionStatus.PROCESSING;
                case FAILED -> ContractCollectionStatus.COULD_NOT_PROCESS;
                case SUCCESS -> {
                    if (entry.contractStatus() == null) {
                        throw new IllegalStateException("Successful document has no contract state");
                    }
                    yield switch (entry.contractStatus()) {
                    case DRAFT -> ContractCollectionStatus.NEEDS_REVIEW;
                    case ACTIVE -> entry.needsAttention()
                            ? ContractCollectionStatus.NEEDS_ATTENTION : ContractCollectionStatus.CONFIRMED;
                    case ARCHIVED -> ContractCollectionStatus.CONFIRMED;
                    };
                }
            };
        }
        if (entry.uploadStatus() == UploadStatus.REJECTED || entry.uploadStatus() == UploadStatus.EXPIRED) {
            return ContractCollectionStatus.COULD_NOT_PROCESS;
        }
        if (entry.uploadStatus() == UploadStatus.COMPLETED) {
            throw new IllegalStateException("Completed upload has no owner collection document");
        }
        return ContractCollectionStatus.PROCESSING;
    }

    public record ContractCollectionItem(UUID id, ContractType type,
                                         ContractCollectionStatus status, String sourceFilename,
                                         OffsetDateTime createdAt, String provider,
                                         ContractPricingModel pricingModel, LocalDate endDate) { }
}
