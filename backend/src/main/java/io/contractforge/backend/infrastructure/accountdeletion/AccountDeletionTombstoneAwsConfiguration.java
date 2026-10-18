package io.contractforge.backend.infrastructure.accountdeletion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.rds.RdsClient;

@Configuration(proxyBeanMethods = false)
@Profile("aws & !local & !test")
class AccountDeletionTombstoneAwsConfiguration {
    @Bean
    DynamoDbClient accountDeletionTombstoneDynamoDbClient(@Value("${AWS_REGION:eu-west-1}") String region) {
        return DynamoDbClient.builder().region(Region.of(region)).build();
    }

    @Bean
    KmsClient accountDeletionFingerprintKmsClient(@Value("${AWS_REGION:eu-west-1}") String region) {
        return KmsClient.builder().region(Region.of(region)).build();
    }

    @Bean
    @Profile("restore-reconciliation")
    RdsClient accountDeletionRecoveryRdsClient(@Value("${AWS_REGION:eu-west-1}") String region) {
        return RdsClient.builder().region(Region.of(region)).build();
    }
}
