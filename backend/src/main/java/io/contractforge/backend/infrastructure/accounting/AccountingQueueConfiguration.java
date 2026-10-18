package io.contractforge.backend.infrastructure.accounting;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AccountingQueueProperties.class)
public class AccountingQueueConfiguration { }
