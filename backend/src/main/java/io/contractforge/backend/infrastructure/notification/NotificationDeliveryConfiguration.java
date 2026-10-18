package io.contractforge.backend.infrastructure.notification;

import io.contractforge.backend.application.notification.NotificationDeliveryProperties;
import io.contractforge.backend.infrastructure.aws.AwsEndpointProperties;
import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

@Configuration
@EnableConfigurationProperties({NotificationDeliveryProperties.class, NotificationDeliverySenderProperties.class})
public class NotificationDeliveryConfiguration {
    @Bean
    SesClient sesClient(NotificationDeliverySenderProperties properties, AwsEndpointProperties endpoints) {
        var builder = SesClient.builder().region(Region.of(properties.region()));
        if (endpoints.configured()) {
            builder.endpointOverride(URI.create(endpoints.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }
}
