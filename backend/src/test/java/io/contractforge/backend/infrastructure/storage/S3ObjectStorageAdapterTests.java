package io.contractforge.backend.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.contractforge.backend.application.upload.UploadProperties;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

class S3ObjectStorageAdapterTests {
    private static final Duration EXPIRY = Duration.ofMinutes(15);

    @Test
    void presignsOnlyTheConfiguredQuarantineObjectWithExpectedConstraints() throws Exception {
        S3Presigner presigner = mock(S3Presigner.class);
        PresignedPutObjectRequest signed = mock(PresignedPutObjectRequest.class);
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(signed);
        when(signed.url()).thenReturn(new URL("https://s3.example.test/quarantine/incoming/id"));
        UploadProperties uploadProperties = new UploadProperties(100, EXPIRY, List.of("application/pdf"),
                "quarantine/incoming/");
        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(presigner,
                new S3StorageProperties("private-quarantine", "eu-west-1", "private/trusted/documents/"), uploadProperties);

        var instructions = adapter.presignPut("quarantine/incoming/upload-id", "application/pdf", 42);

        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(presigner).presignPutObject(captor.capture());
        PutObjectPresignRequest presign = captor.getValue();
        PutObjectRequest object = presign.putObjectRequest();
        assertThat(presign.signatureDuration()).isEqualTo(EXPIRY);
        assertThat(object.bucket()).isEqualTo("private-quarantine");
        assertThat(object.key()).isEqualTo("quarantine/incoming/upload-id");
        assertThat(object.contentType()).isEqualTo("application/pdf");
        assertThat(object.contentLength()).isEqualTo(42);
        assertThat(instructions.headers()).containsEntry("Content-Type", "application/pdf")
                .containsEntry("Content-Length", "42");
    }

    @Test
    void rejectsKeysOutsideTheQuarantineNamespace() {
        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(mock(S3Presigner.class),
                new S3StorageProperties("private-quarantine", "eu-west-1", "private/trusted/documents/"),
                new UploadProperties(100, EXPIRY, List.of("application/pdf"), "quarantine/incoming/"));

        assertThatThrownBy(() -> adapter.presignPut("trusted/document", "application/pdf", 42))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void presignsOnlyAnAvailableTrustedPdfForInlineReading() throws Exception {
        S3Client s3 = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        PresignedGetObjectRequest signed = mock(PresignedGetObjectRequest.class);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder().build());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(signed);
        when(signed.url()).thenReturn(new URL("https://s3.example.test/private/document?signature=redacted"));
        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(presigner, s3,
                new S3StorageProperties("private-quarantine", "eu-west-1", "private/trusted/documents/"),
                new UploadProperties(100, EXPIRY, List.of("application/pdf"), "quarantine/incoming/"));

        var target = adapter.presignGet("private/trusted/documents/object", Duration.ofMinutes(5));

        assertThat(target.url().toString()).contains("s3.example.test");
        verify(s3).headObject(HeadObjectRequest.builder().bucket("private-quarantine")
                .key("private/trusted/documents/object").build());
        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(captor.capture());
        GetObjectPresignRequest request = captor.getValue();
        GetObjectRequest object = request.getObjectRequest();
        assertThat(request.signatureDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(object.responseContentType()).isEqualTo("application/pdf");
        assertThat(object.responseContentDisposition()).isEqualTo("inline");
        assertThat(object.responseCacheControl()).isEqualTo("no-store");
    }

    @Test
    void refusesUnavailableSourceAndKeysOutsideTrustedNamespace() {
        S3Client s3 = mock(S3Client.class);
        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(mock(S3Presigner.class), s3,
                new S3StorageProperties("private-quarantine", "eu-west-1", "private/trusted/documents/"),
                new UploadProperties(100, EXPIRY, List.of("application/pdf"), "quarantine/incoming/"));
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(
                software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build());

        assertThatThrownBy(() -> adapter.presignGet("private/trusted/documents/missing", Duration.ofMinutes(5)))
                .isInstanceOf(io.contractforge.backend.application.port.ObjectStoragePort.SourceObjectMissingException.class);
        assertThatThrownBy(() -> adapter.presignGet("quarantine/incoming/not-trusted", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesOnlyTrustedPrivateObjects() {
        S3Client s3 = mock(S3Client.class);
        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(mock(S3Presigner.class), s3,
                new S3StorageProperties("private-quarantine", "eu-west-1", "private/trusted/documents/"),
                new UploadProperties(100, EXPIRY, List.of("application/pdf"), "quarantine/incoming/"));

        adapter.deletePrivateObject("private/trusted/documents/object");

        verify(s3).deleteObject(DeleteObjectRequest.builder().bucket("private-quarantine")
                .key("private/trusted/documents/object").build());
    }

    @Test
    void rejectsPrivateDeletionOutsideTrustedNamespace() {
        S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(mock(S3Presigner.class), mock(S3Client.class),
                new S3StorageProperties("private-quarantine", "eu-west-1", "private/trusted/documents/"),
                new UploadProperties(100, EXPIRY, List.of("application/pdf"), "quarantine/incoming/"));
        assertThatThrownBy(() -> adapter.deletePrivateObject("quarantine/incoming/object"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
