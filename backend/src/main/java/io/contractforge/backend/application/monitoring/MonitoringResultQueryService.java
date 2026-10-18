package io.contractforge.backend.application.monitoring;

import io.contractforge.backend.application.port.RepositoryPorts.EventRepository;
import io.contractforge.backend.application.port.RepositoryPorts.RecommendationRepository;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.domain.model.PersistenceModels.Event;
import io.contractforge.backend.domain.model.PersistenceModels.Recommendation;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads persisted monitoring results for the server-resolved owner without re-running monitoring. */
@Service
@Profile("!test")
public class MonitoringResultQueryService {

    private final EventRepository events;
    private final RecommendationRepository recommendations;
    private final Clock clock;

    public MonitoringResultQueryService(EventRepository events, RecommendationRepository recommendations, Clock clock) {
        this.events = events;
        this.recommendations = recommendations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Event> events(AuthenticatedApplicationUser user) {
        requireUser(user);
        return events.findByUserId(user.userId());
    }

    @Transactional(readOnly = true)
    public List<Recommendation> recommendations(AuthenticatedApplicationUser user, boolean includeHistory) {
        requireUser(user);
        return recommendations.findByUserId(user.userId(), includeHistory, OffsetDateTime.now(clock));
    }

    private void requireUser(AuthenticatedApplicationUser user) {
        if (user == null || user.userId() == null) {
            throw new IllegalArgumentException("Authenticated application user is required");
        }
    }
}
