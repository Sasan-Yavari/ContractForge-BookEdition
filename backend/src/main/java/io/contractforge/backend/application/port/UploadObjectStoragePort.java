package io.contractforge.backend.application.port;

import java.util.Map;
import java.util.UUID;

/** Application-facing read and cleanup boundary for objects in upload quarantine. */
public interface UploadObjectStoragePort {

    class TrustedObjectNotFoundException extends RuntimeException {
        public TrustedObjectNotFoundException(Throwable cause) { super(cause); }
    }

    ObjectMetadata head(String objectKey);

    String trustedKeyFor(UUID uploadId);

    void promoteToTrusted(String sourceKey, String trustedKey);

    ObjectMetadata headTrusted(String objectKey);

    byte[] readPrefix(String objectKey, int maximumBytes);

    /** Reads a complete trusted object only when it is within the caller's byte limit. */
    default byte[] readTrusted(String objectKey, int maximumBytes) {
        return readPrefix(objectKey, maximumBytes);
    }

    void deleteQuarantineObject(String objectKey);

    /** Deletes the deterministic trusted object for a late event after its upload row was erased. */
    default void deleteTrustedObjectFor(UUID uploadId) {
        throw new UnsupportedOperationException("Trusted object cleanup is unavailable");
    }

    record ObjectMetadata(String objectKey, long contentLength, String contentType,
                          Map<String, String> metadata) {
        public ObjectMetadata {
            if (objectKey == null || objectKey.isBlank() || contentLength < 0
                    || contentType == null || contentType.isBlank() || metadata == null) {
                throw new IllegalArgumentException("Object metadata is malformed");
            }
            metadata = Map.copyOf(metadata);
        }
    }
}
