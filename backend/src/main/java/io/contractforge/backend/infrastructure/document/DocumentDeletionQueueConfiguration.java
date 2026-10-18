package io.contractforge.backend.infrastructure.document;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DocumentDeletionQueueProperties.class)
public class DocumentDeletionQueueConfiguration { }
