package io.contractforge.backend.application.upload;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.upload")
public record UploadProperties(long maxSizeBytes, Duration expiry, List<String> supportedContentTypes,
                               String objectKeyPrefix) {
    public static final String QUARANTINE_OBJECT_KEY_PREFIX = "quarantine/incoming/";

    public UploadProperties {
        supportedContentTypes = supportedContentTypes == null ? List.of() : supportedContentTypes.stream()
                .map(type -> type.toLowerCase(Locale.ROOT))
                .toList();
    }
}
