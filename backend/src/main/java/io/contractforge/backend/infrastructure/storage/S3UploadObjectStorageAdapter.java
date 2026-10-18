package io.contractforge.backend.infrastructure.storage;

import io.contractforge.backend.application.port.UploadObjectStoragePort;
import io.contractforge.backend.application.upload.UploadProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.util.UUID;

/** S3 quarantine adapter; AWS SDK types do not cross the application boundary. */
@Component
@Profile("!test")
final class S3UploadObjectStorageAdapter implements UploadObjectStoragePort {
    private final S3Client s3;
    private final S3StorageProperties properties;

    S3UploadObjectStorageAdapter(S3Client s3, S3StorageProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @Override
    public ObjectMetadata head(String objectKey) {
        var response = s3.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
        return new ObjectMetadata(objectKey, response.contentLength(), response.contentType(), response.metadata());
    }

    @Override
    public String trustedKeyFor(UUID uploadId) {
        if (uploadId == null || properties.trustedObjectKeyPrefix() == null
                || !properties.trustedObjectKeyPrefix().startsWith("private/trusted/")) {
            throw new IllegalArgumentException("Trusted storage namespace is not configured");
        }
        return properties.trustedObjectKeyPrefix() + uploadId;
    }

    @Override
    public void promoteToTrusted(String sourceKey, String trustedKey) {
        validateQuarantineKey(sourceKey);
        validateTrustedKey(trustedKey);
        s3.copyObject(CopyObjectRequest.builder().copySource(properties.bucket() + "/" + sourceKey)
                .destinationBucket(properties.bucket()).destinationKey(trustedKey).build());
    }

    @Override
    public ObjectMetadata headTrusted(String objectKey) {
        validateTrustedKey(objectKey);
        try {
            var response = s3.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
            return new ObjectMetadata(objectKey, response.contentLength(), response.contentType(), response.metadata());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new UploadObjectStoragePort.TrustedObjectNotFoundException(exception);
            }
            throw exception;
        }
    }

    @Override
    public byte[] readPrefix(String objectKey, int maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("Prefix length must be positive");
        }
        ResponseBytes<GetObjectResponse> response = s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(properties.bucket()).key(objectKey).range("bytes=0-" + (maximumBytes - 1)).build());
        return response.asByteArray();
    }

    @Override
    public byte[] readTrusted(String objectKey, int maximumBytes) {
        validateTrustedKey(objectKey);
        if (maximumBytes <= 0) throw new IllegalArgumentException("Maximum object size must be positive");
        ObjectMetadata metadata = headTrusted(objectKey);
        if (metadata.contentLength() > maximumBytes) throw new IllegalArgumentException("Trusted object exceeds extraction limit");
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build()).asByteArray();
    }

    @Override
    public void deleteQuarantineObject(String objectKey) {
        validateQuarantineKey(objectKey);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
    }

    @Override
    public void deleteTrustedObjectFor(UUID uploadId) {
        String key = trustedKeyFor(uploadId);
        validateTrustedKey(key);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
    }

    private void validateQuarantineKey(String key) {
        if (key == null || !key.startsWith(UploadProperties.QUARANTINE_OBJECT_KEY_PREFIX) || key.contains("..")) {
            throw new IllegalArgumentException("Object key is outside the quarantine namespace");
        }
    }

    private void validateTrustedKey(String key) {
        if (key == null || properties.trustedObjectKeyPrefix() == null
                || !key.startsWith(properties.trustedObjectKeyPrefix()) || key.contains("..")) {
            throw new IllegalArgumentException("Object key is outside the trusted namespace");
        }
    }
}
