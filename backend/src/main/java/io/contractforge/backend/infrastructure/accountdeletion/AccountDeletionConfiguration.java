package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.AccountDeletionProperties;
import io.contractforge.backend.application.accountdeletion.AccountDeletionQueueProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AccountDeletionProperties.class, AccountDeletionQueueProperties.class})
public class AccountDeletionConfiguration { }
