package io.contractforge.backend.infrastructure.storage;

import io.contractforge.backend.application.port.ObjectStoragePort;
import io.contractforge.backend.application.upload.UploadProperties;
import java.net.URI;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.time.Duration;

@Component
@Profile("!test")
final class S3ObjectStorageAdapter implements ObjectStoragePort {
    private final S3Presigner presigner;
    private final S3Client s3;
    private final S3StorageProperties properties;
    private final UploadProperties uploadProperties;

    @Autowired
    S3ObjectStorageAdapter(S3Presigner presigner, S3Client s3, S3StorageProperties properties,
                            UploadProperties uploadProperties) {
        this.presigner = presigner; this.s3 = s3;
        this.properties = properties;
        this.uploadProperties = uploadProperties;
    }

    S3ObjectStorageAdapter(S3Presigner presigner, S3StorageProperties properties,
                            UploadProperties uploadProperties) {
        this(presigner, S3Client.builder().region(software.amazon.awssdk.regions.Region.of(properties.region())).build(),
                properties, uploadProperties);
    }

    @Override
    public ObjectStoragePort.UploadInstructions presignPut(String objectKey, String contentType, long contentLength) {
        if (objectKey == null || !objectKey.startsWith(uploadProperties.objectKeyPrefix())
                || !objectKey.startsWith(UploadProperties.QUARANTINE_OBJECT_KEY_PREFIX) || objectKey.contains("..")) {
            throw new IllegalArgumentException("Object key is outside the quarantine namespace");
        }
        PutObjectRequest object = PutObjectRequest.builder().bucket(properties.bucket()).key(objectKey)
                .contentType(contentType).contentLength(contentLength).build();
        var request = PutObjectPresignRequest.builder().signatureDuration(uploadProperties.expiry())
                .putObjectRequest(object).build();
        var presigned = presigner.presignPutObject(request);
        return new ObjectStoragePort.UploadInstructions(URI.create(presigned.url().toString()),
                Map.of("Content-Type", contentType, "Content-Length", Long.toString(contentLength)));
    }

    @Override
    public ObjectStoragePort.PresignedRead presignGet(String objectKey, Duration validity) {
        if (objectKey == null || !objectKey.startsWith(properties.trustedObjectKeyPrefix())
                || objectKey.contains("..") || validity == null || validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("Object key or read validity is invalid");
        }
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
            GetObjectRequest object = GetObjectRequest.builder().bucket(properties.bucket()).key(objectKey)
                    .responseContentType("application/pdf").responseContentDisposition("inline")
                    .responseCacheControl("no-store").build();
            var request = GetObjectPresignRequest.builder().signatureDuration(validity)
                    .getObjectRequest(object).build();
            return new ObjectStoragePort.PresignedRead(URI.create(presigner.presignGetObject(request).url().toString()));
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) throw new ObjectStoragePort.SourceObjectMissingException();
            throw new ObjectStoragePort.StorageUnavailableException(failure);
        } catch (RuntimeException failure) {
            if (failure instanceof ObjectStoragePort.SourceObjectMissingException
                    || failure instanceof ObjectStoragePort.StorageUnavailableException) throw failure;
            throw new ObjectStoragePort.StorageUnavailableException(failure);
        }
    }

    @Override
    public void deletePrivateObject(String objectKey) {
        if (objectKey == null || !objectKey.startsWith(properties.trustedObjectKeyPrefix()) || objectKey.contains("..")) {
            throw new IllegalArgumentException("Object key is outside the trusted document namespace");
        }
        s3.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
    }
}
