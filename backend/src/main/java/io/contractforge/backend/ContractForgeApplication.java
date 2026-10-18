package io.contractforge.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.contractforge.backend.application.upload.UploadProperties;
import io.contractforge.backend.application.upload.UploadRateLimitProperties;
import io.contractforge.backend.infrastructure.aws.AwsEndpointProperties;
import java.util.Map;

@SpringBootApplication
@EnableConfigurationProperties({UploadProperties.class, UploadRateLimitProperties.class, AwsEndpointProperties.class})
public class ContractForgeApplication {

    public static void main(String[] args) {
        if (java.util.Arrays.stream(args).anyMatch(argument -> argument.startsWith("--restore-reconcile"))) {
            if (java.util.Arrays.stream(args).anyMatch(argument -> argument.equals("--restore-reconcile=release"))) {
                runTombstoneRelease(args);
            } else {
                runRestoreReconciliation(args);
            }
            return;
        }
        SpringApplication.run(ContractForgeApplication.class, args);
    }

    private static void runRestoreReconciliation(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ContractForgeApplication.class)
                .profiles("aws", "restore-reconciliation").web(WebApplicationType.NONE)
                .initializers(application -> {
                    ConfigurableEnvironment environment = (ConfigurableEnvironment) application.getEnvironment();
                    environment.getPropertySources().addFirst(new MapPropertySource("restoreReconciliation",
                            Map.of("spring.flyway.enabled", "false",
                                    "contractforge.processing.queue.queue-url", "",
                                    "contractforge.upload.ingestion.queue.queue-url", "",
                                    "contractforge.document-deletion.queue.queue-url", "",
                                    "contractforge.account-deletion.queue.queue-url", "",
                                    "contractforge.accounting.queue.queue-url", "",
                                    "contractforge.notification.queue.queue-url", "",
                                    "contractforge.monitoring.schedule.enabled", "false",
                                    "contractforge.notification.queue.recovery-enabled", "false")));
                }).run(args)) {
            int exitCode = SpringApplication.exit(context);
            if (exitCode != 0) System.exit(exitCode);
        }
    }

    private static void runTombstoneRelease(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                io.contractforge.backend.infrastructure.accountdeletion.RestoreTombstoneReleaseConfiguration.class)
                .profiles("restore-tombstone-release").web(WebApplicationType.NONE).run(args)) {
            int exitCode = SpringApplication.exit(context);
            if (exitCode != 0) System.exit(exitCode);
        }
    }
}
