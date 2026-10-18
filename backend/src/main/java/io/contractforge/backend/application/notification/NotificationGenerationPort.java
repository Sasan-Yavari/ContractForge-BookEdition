package io.contractforge.backend.application.notification;

import io.contractforge.backend.domain.model.PersistenceModels.Recommendation;

/** Application boundary for generating durable notification state from a Recommendation. */
public interface NotificationGenerationPort {
    void generate(Recommendation recommendation);
}
