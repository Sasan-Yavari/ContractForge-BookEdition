package io.contractforge.backend.infrastructure.storage;

import io.contractforge.backend.infrastructure.aws.AwsEndpointProperties;
import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

@Configuration
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfiguration {
    @Bean
    S3Presigner s3Presigner(S3StorageProperties properties, AwsEndpointProperties endpoints) {
        var builder = S3Presigner.builder().region(Region.of(properties.region()));
        if (endpoints.configured()) {
            builder.endpointOverride(URI.create(endpoints.presignEndpoint()))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }

    @Bean
    S3Client s3Client(S3StorageProperties properties, AwsEndpointProperties endpoints) {
        var builder = S3Client.builder().region(Region.of(properties.region()));
        if (endpoints.configured()) {
            builder.endpointOverride(URI.create(endpoints.endpoint())).forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }
}
