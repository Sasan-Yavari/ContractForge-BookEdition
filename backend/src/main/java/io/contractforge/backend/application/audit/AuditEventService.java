package io.contractforge.backend.application.audit;

import io.contractforge.backend.application.port.RepositoryPorts.AuditEventRepository;
import io.contractforge.backend.domain.model.PersistenceModels.AuditEvent;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Application boundary for appending minimal, append-only audit events. */
@Service
@Profile("!test")
public class AuditEventService {

    private final AuditEventRepository auditEventRepository;

    public AuditEventService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    public void append(UUID actorUserId, ResourceType resourceType, UUID resourceId, String action) {
        if (actorUserId == null || resourceType == null || resourceId == null || action == null || action.isBlank()) {
            throw new IllegalArgumentException("Audit event fields must be present");
        }
        auditEventRepository.append(new AuditEvent(UUID.randomUUID(), actorUserId, resourceType.name(), resourceId,
                action, OffsetDateTime.now()));
    }

    public enum ResourceType {
        USER,
        DOCUMENT,
        CONTRACT,
        EVENT,
        RECOMMENDATION,
        UPLOAD
    }
}
