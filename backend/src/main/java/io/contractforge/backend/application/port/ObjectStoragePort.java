package io.contractforge.backend.application.port;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Application-facing boundary for a narrowly scoped direct upload. */
public interface ObjectStoragePort {

    UploadInstructions presignPut(String objectKey, String contentType, long contentLength);

    default PresignedRead presignGet(String objectKey, Duration validity) {
        throw new UnsupportedOperationException("Private document reads are not configured");
    }

    /** Deletes a trusted private document object. Implementations must treat an absent object as success. */
    default void deletePrivateObject(String objectKey) {
        throw new UnsupportedOperationException("Private object deletion is not configured");
    }

    record UploadInstructions(URI url, Map<String, String> headers) {
        public UploadInstructions {
            if (url == null || headers == null) {
                throw new IllegalArgumentException("Upload instructions must be complete");
            }
            headers = Map.copyOf(headers);
        }
    }

    record PresignedRead(URI url) {
        public PresignedRead {
            if (url == null) throw new IllegalArgumentException("Read target must include a URL");
        }
    }

    final class SourceObjectMissingException extends RuntimeException { }

    final class StorageUnavailableException extends RuntimeException {
        public StorageUnavailableException(Throwable cause) { super(cause); }
    }
}
