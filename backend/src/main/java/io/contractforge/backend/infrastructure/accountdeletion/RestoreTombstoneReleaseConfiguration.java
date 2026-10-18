package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstonePort;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.rds.RdsClient;

/** Minimal AWS-only context used to release a tombstone when the source database is stopped or absent. */
@Configuration(proxyBeanMethods = false)
@Profile("restore-tombstone-release")
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
public class RestoreTombstoneReleaseConfiguration {
    @Bean
    DynamoDbClient releaseDynamoDbClient(@Value("${AWS_REGION:eu-west-1}") String region) {
        return DynamoDbClient.builder().region(Region.of(region)).build();
    }

    @Bean
    KmsClient releaseFingerprintKmsClient(@Value("${AWS_REGION:eu-west-1}") String region) {
        return KmsClient.builder().region(Region.of(region)).build();
    }

    @Bean
    RdsClient releaseRdsClient(@Value("${AWS_REGION:eu-west-1}") String region) {
        return RdsClient.builder().region(Region.of(region)).build();
    }

    @Bean
    AccountDeletionTombstonePort releaseTombstones(DynamoDbClient dynamo, KmsClient kms,
            @Value("${CONTRACTFORGE_ACCOUNT_DELETION_TOMBSTONE_TABLE:}") String tableName,
            @Value("${CONTRACTFORGE_ACCOUNT_DELETION_FINGERPRINT_KEY_ARN:}") String fingerprintKeyArn) {
        return new AwsAccountDeletionTombstoneAdapter(dynamo, kms, tableName, fingerprintKeyArn, "");
    }

    @Bean
    RdsRecoveryPointInventory releaseRecoveryPointInventory(RdsClient rds) {
        return new RdsRecoveryPointInventory(rds);
    }

    @Bean
    RestoreTombstoneReleaseRunner restoreTombstoneReleaseRunner(AccountDeletionTombstonePort tombstones,
            RdsRecoveryPointInventory recoveryPoints, Clock clock) {
        return new RestoreTombstoneReleaseRunner(tombstones, recoveryPoints, clock);
    }

    @Bean
    Clock restoreReleaseClock() {
        return Clock.systemUTC();
    }
}
