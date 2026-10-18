package io.contractforge.backend.infrastructure.accountdeletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository.ExternalIdentity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GenerateMacRequest;
import software.amazon.awssdk.services.kms.model.GenerateMacResponse;

class AwsAccountDeletionTombstoneAdapterTests {
    @Test
    void atomicallyStoresOnlyOpaqueIdentityFingerprintsAndTheApprovedTombstoneFields() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        KmsClient kms = mock(KmsClient.class);
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of()).build());
        when(kms.generateMac(any(GenerateMacRequest.class))).thenReturn(
                GenerateMacResponse.builder().mac(SdkBytes.fromByteArray(new byte[32])).build());
        var adapter = new AwsAccountDeletionTombstoneAdapter(dynamo, kms, "dev-tombstones", "arn:key",
                "db-resource-1");
        String provider = "cognito";
        String subject = "raw-provider-subject-must-not-be-stored";
        UUID deletionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        adapter.recordInProgress(deletionId, userId, List.of(new ExternalIdentity(provider, subject)),
                OffsetDateTime.parse("2026-09-01T12:00:00Z"));

        ArgumentCaptor<TransactWriteItemsRequest> transaction = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamo).transactWriteItems(transaction.capture());
        assertThat(transaction.getValue().transactItems()).hasSize(2);
        var subjectItem = transaction.getValue().transactItems().stream().map(item -> item.put().item())
                .filter(item -> "SUBJECT".equals(item.get("record_type").s())).findFirst().orElseThrow();
        var deletionItem = transaction.getValue().transactItems().stream().map(item -> item.put().item())
                .filter(item -> "DELETION".equals(item.get("record_type").s())).findFirst().orElseThrow();
        assertThat(subjectItem.toString()).doesNotContain(subject).doesNotContain(provider);
        assertThat(deletionItem.toString()).doesNotContain(subject).doesNotContain(provider);
        assertThat(deletionItem).containsKeys("deletion_id", "user_id", "source_db_resource_id", "fingerprints",
                "state", "fenced_at");
        assertThat(deletionItem.keySet()).doesNotContain("email", "object_key", "provider_subject", "provider");
        assertThat(subjectItem.get("fingerprint").s()).startsWith("v1_");

        ArgumentCaptor<GenerateMacRequest> mac = ArgumentCaptor.forClass(GenerateMacRequest.class);
        verify(kms).generateMac(mac.capture());
        assertThat(mac.getValue().keyId()).isEqualTo("arn:key");
        assertThat(mac.getValue().macAlgorithmAsString()).isEqualTo("HMAC_SHA_256");
        assertThat(decodeMacInput(mac.getValue().message().asByteArray())).contains(provider, subject);
    }

    private static String decodeMacInput(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        byte[] domain = new byte["contractforge:deleted-provider-subject:v1".length()];
        buffer.get(domain);
        int providerLength = buffer.getInt();
        byte[] provider = new byte[providerLength];
        buffer.get(provider);
        int subjectLength = buffer.getInt();
        byte[] subject = new byte[subjectLength];
        buffer.get(subject);
        return new String(domain, StandardCharsets.UTF_8) + new String(provider, StandardCharsets.UTF_8)
                + new String(subject, StandardCharsets.UTF_8);
    }
}
