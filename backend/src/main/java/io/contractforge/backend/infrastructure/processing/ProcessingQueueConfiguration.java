package io.contractforge.backend.infrastructure.processing;

import io.contractforge.backend.infrastructure.aws.AwsEndpointProperties;
import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

@Configuration
@EnableConfigurationProperties(ProcessingQueueProperties.class)
public class ProcessingQueueConfiguration {
    @Bean
    SqsClient sqsClient(ProcessingQueueProperties properties, AwsEndpointProperties endpoints) {
        var builder = SqsClient.builder().region(Region.of(System.getenv().getOrDefault("AWS_REGION", "eu-west-1")));
        if (endpoints.configured()) {
            builder.endpointOverride(URI.create(endpoints.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }
}
