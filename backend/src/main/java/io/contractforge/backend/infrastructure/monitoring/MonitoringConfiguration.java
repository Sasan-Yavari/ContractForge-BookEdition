package io.contractforge.backend.infrastructure.monitoring;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MonitoringConfiguration {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }
}
