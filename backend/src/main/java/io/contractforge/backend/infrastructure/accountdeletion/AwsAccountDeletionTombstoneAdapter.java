package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository.ExternalIdentity;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstone;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstonePort;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GenerateMacRequest;
import software.amazon.awssdk.services.kms.model.MacAlgorithmSpec;

/** AWS KMS/DynamoDB boundary for minimal account-deletion tombstones. */
@Component
@Profile("aws & !local & !test")
final class AwsAccountDeletionTombstoneAdapter implements AccountDeletionTombstonePort {
    private static final String DELETION_PREFIX = "DELETION#";
    private static final String SUBJECT_PREFIX = "SUBJECT#";
    private static final String FINGERPRINT_VERSION = "v1_";
    private static final byte[] FINGERPRINT_DOMAIN = "contractforge:deleted-provider-subject:v1"
            .getBytes(StandardCharsets.UTF_8);

    private final DynamoDbClient dynamo;
    private final KmsClient kms;
    private final String tableName;
    private final String fingerprintKeyArn;
    private final String sourceDbResourceId;

    AwsAccountDeletionTombstoneAdapter(DynamoDbClient dynamo, KmsClient kms,
                                       @Value("${CONTRACTFORGE_ACCOUNT_DELETION_TOMBSTONE_TABLE:}") String tableName,
                                       @Value("${CONTRACTFORGE_ACCOUNT_DELETION_FINGERPRINT_KEY_ARN:}") String fingerprintKeyArn,
                                       @Value("${CONTRACTFORGE_DB_RESOURCE_ID:}") String sourceDbResourceId) {
        if (tableName == null || tableName.isBlank() || fingerprintKeyArn == null || fingerprintKeyArn.isBlank()) {
            throw new IllegalStateException("Account deletion tombstone storage is not configured");
        }
        this.dynamo = dynamo;
        this.kms = kms;
        this.tableName = tableName;
        this.fingerprintKeyArn = fingerprintKeyArn;
        this.sourceDbResourceId = sourceDbResourceId;
    }

    @Override
    public String fingerprint(String provider, String providerSubject) {
        if (provider == null || provider.isBlank() || providerSubject == null || providerSubject.isBlank()) {
            throw new IllegalArgumentException("External identity is incomplete");
        }
        byte[] providerBytes = provider.getBytes(StandardCharsets.UTF_8);
        byte[] subjectBytes = providerSubject.getBytes(StandardCharsets.UTF_8);
        ByteBuffer message = ByteBuffer.allocate(FINGERPRINT_DOMAIN.length + Integer.BYTES + providerBytes.length
                + Integer.BYTES + subjectBytes.length);
        message.put(FINGERPRINT_DOMAIN).putInt(providerBytes.length).put(providerBytes)
                .putInt(subjectBytes.length).put(subjectBytes);
        byte[] mac = kms.generateMac(GenerateMacRequest.builder().keyId(fingerprintKeyArn)
                .macAlgorithm(MacAlgorithmSpec.HMAC_SHA_256).message(SdkBytes.fromByteArray(message.array()))
                .build()).mac().asByteArray();
        return FINGERPRINT_VERSION + HexFormat.of().formatHex(mac);
    }

    @Override
    public void recordInProgress(UUID deletionId, UUID userId, List<ExternalIdentity> identities,
                                 OffsetDateTime fencedAt) {
        requireOperation(deletionId, userId);
        if (identities == null || identities.isEmpty() || fencedAt == null
                || sourceDbResourceId == null || sourceDbResourceId.isBlank()) {
            throw new IllegalArgumentException("Account deletion identity fence is incomplete");
        }
        List<String> fingerprints = identities.stream()
                .map(identity -> fingerprint(identity.provider(), identity.providerSubject()))
                .distinct().sorted().toList();
        if (fingerprints.size() != identities.size()) {
            throw new IllegalStateException("Account deletion identity fingerprints are not unique");
        }

        if (fingerprints.size() >= 100) {
            throw new IllegalStateException("Account deletion has too many provider identities for atomic fencing");
        }
        Optional<Map<String, AttributeValue>> existing = get(deletionKey(deletionId));
        if (existing.isPresent()) {
            requireSameOperation(existing.get(), deletionId, userId, fingerprints);
            requireSubjectFences(deletionId, userId, fingerprints);
            return;
        }
        List<TransactWriteItem> writes = new ArrayList<>();
        for (String fingerprint : fingerprints) {
            writes.add(TransactWriteItem.builder().put(Put.builder().tableName(tableName)
                    .item(subjectItem(deletionId, userId, fingerprint))
                    .conditionExpression("attribute_not_exists(record_id)").build()).build());
        }
        writes.add(TransactWriteItem.builder().put(Put.builder().tableName(tableName)
                .item(deletionItem(deletionId, userId, sourceDbResourceId, fingerprints, fencedAt))
                .conditionExpression("attribute_not_exists(record_id)").build()).build());
        try {
            dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(writes).build());
        } catch (TransactionCanceledException race) {
            Optional<Map<String, AttributeValue>> stored = get(deletionKey(deletionId));
            if (stored.isEmpty()) throw new IllegalStateException(
                    "Provider identity is already fenced by another deletion", race);
            requireSameOperation(stored.get(), deletionId, userId, fingerprints);
            requireSubjectFences(deletionId, userId, fingerprints);
        }
    }

    @Override
    public boolean blocksProvisioning(String provider, String providerSubject) {
        String fingerprint = fingerprint(provider, providerSubject);
        return get(subjectKey(fingerprint)).isPresent();
    }

    @Override
    public Optional<AccountDeletionTombstone> find(UUID deletionId) {
        if (deletionId == null) throw new IllegalArgumentException("Deletion operation ID is required");
        Optional<Map<String, AttributeValue>> item = get(deletionKey(deletionId));
        if (item.isEmpty()) return Optional.empty();
        AccountDeletionTombstone tombstone = toTombstone(item.get());
        if (!tombstone.deletionId().equals(deletionId)) {
            throw new IllegalStateException("Account deletion tombstone operation ID conflicts");
        }
        requireSubjectFences(deletionId, tombstone.userId(), tombstone.identityFingerprints());
        return Optional.of(tombstone);
    }

    @Override
    public void complete(UUID deletionId, UUID userId, OffsetDateTime completedAt) {
        requireOperation(deletionId, userId);
        if (completedAt == null) throw new IllegalArgumentException("Deletion completion time is required");
        try {
            dynamo.updateItem(UpdateItemRequest.builder().tableName(tableName).key(key(deletionKey(deletionId)))
                    .updateExpression("SET #state = :completed, completed_at = if_not_exists(completed_at, :at)")
                    .conditionExpression("record_type = :type AND user_id = :user AND attribute_exists(fingerprints)")
                    .expressionAttributeNames(Map.of("#state", "state"))
                    .expressionAttributeValues(Map.of(":completed", string(AccountDeletionTombstone.State.COMPLETED.name()),
                            ":at", string(completedAt.toString()), ":type", string("DELETION"),
                            ":user", string(userId.toString())))
                    .build());
        } catch (ConditionalCheckFailedException failure) {
            throw new IllegalStateException("Account deletion tombstone could not be completed", failure);
        }
    }

    @Override
    public List<AccountDeletionTombstone> findAll() {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        var page = dynamo.scan(ScanRequest.builder().tableName(tableName).consistentRead(true).build());
        items.addAll(page.items());
        while (page.lastEvaluatedKey() != null && !page.lastEvaluatedKey().isEmpty()) {
            page = dynamo.scan(ScanRequest.builder().tableName(tableName).consistentRead(true)
                    .exclusiveStartKey(page.lastEvaluatedKey()).build());
            items.addAll(page.items());
        }

        Map<UUID, AccountDeletionTombstone> tombstones = items.stream()
                .filter(item -> "DELETION".equals(value(item, "record_type")))
                .map(AwsAccountDeletionTombstoneAdapter::toTombstone)
                .collect(java.util.stream.Collectors.toMap(AccountDeletionTombstone::deletionId, item -> item));
        java.util.Set<String> indexedSubjectFences = new java.util.HashSet<>();
        for (Map<String, AttributeValue> item : items) {
            if (!"SUBJECT".equals(value(item, "record_type"))) continue;
            UUID deletionId = UUID.fromString(value(item, "deletion_id"));
            AccountDeletionTombstone tombstone = tombstones.get(deletionId);
            String fingerprint = value(item, "fingerprint");
            if (tombstone == null || !tombstone.identityFingerprints().contains(fingerprint)
                    || !tombstone.userId().toString().equals(value(item, "user_id"))) {
                throw new IllegalStateException("Account deletion tombstone index is incomplete");
            }
            indexedSubjectFences.add(deletionId + ":" + fingerprint);
        }
        for (AccountDeletionTombstone tombstone : tombstones.values()) {
            for (String fingerprint : tombstone.identityFingerprints()) {
                if (!indexedSubjectFences.contains(tombstone.deletionId() + ":" + fingerprint)) {
                    throw new IllegalStateException("Account deletion tombstone subject fence is missing");
                }
            }
        }
        return tombstones.values().stream().sorted(Comparator.comparing(AccountDeletionTombstone::deletionId)).toList();
    }

    @Override
    public void recordRecoveryCheck(UUID deletionId, OffsetDateTime checkedAt,
                                    Optional<OffsetDateTime> oldestRecoverablePointAt,
                                    int recoverablePointCount) {
        if (deletionId == null || checkedAt == null || oldestRecoverablePointAt == null || recoverablePointCount < 0
                || (recoverablePointCount == 0 && oldestRecoverablePointAt.isPresent())) {
            throw new IllegalArgumentException("Recovery-point assessment is incomplete");
        }
        var request = UpdateItemRequest.builder().tableName(tableName).key(key(deletionKey(deletionId)))
                .updateExpression(oldestRecoverablePointAt.isPresent()
                        ? "SET last_recovery_check_at = :checked, oldest_recoverable_point_at = :oldest, recoverable_point_count = :count"
                        : "SET last_recovery_check_at = :checked, recoverable_point_count = :count REMOVE oldest_recoverable_point_at")
                .conditionExpression("record_type = :type AND #state = :completed AND attribute_exists(completed_at)")
                .expressionAttributeNames(Map.of("#state", "state"))
                .expressionAttributeValues(Map.of(":checked", string(checkedAt.toString()),
                        ":count", number(recoverablePointCount), ":type", string("DELETION"),
                        ":completed", string(AccountDeletionTombstone.State.COMPLETED.name())));
        oldestRecoverablePointAt.ifPresent(time -> request.expressionAttributeValues(
                Map.of(":checked", string(checkedAt.toString()), ":count", number(recoverablePointCount),
                        ":type", string("DELETION"), ":completed", string(AccountDeletionTombstone.State.COMPLETED.name()),
                        ":oldest", string(time.toString()))));
        try {
            dynamo.updateItem(request.build());
        } catch (ConditionalCheckFailedException failure) {
            throw new IllegalStateException("Only completed account deletion tombstones can be recovery-checked", failure);
        }
    }

    @Override
    public void releaseIfNoRecoverablePoints(UUID deletionId) {
        Map<String, AttributeValue> item = get(deletionKey(deletionId))
                .orElseThrow(() -> new IllegalStateException("Account deletion tombstone does not exist"));
        AccountDeletionTombstone tombstone = toTombstone(item);
        if (tombstone.state() != AccountDeletionTombstone.State.COMPLETED
                || tombstone.lastRecoveryCheckAt() == null || tombstone.recoverablePointCount() != 0) {
            throw new IllegalStateException("Account deletion tombstone still has a recovery hold");
        }
        if (tombstone.identityFingerprints().size() >= 100) {
            throw new IllegalStateException("Tombstone has too many identity fingerprints for atomic release");
        }
        List<TransactWriteItem> deletes = new ArrayList<>();
        for (String fingerprint : tombstone.identityFingerprints()) {
            deletes.add(TransactWriteItem.builder().delete(Delete.builder().tableName(tableName)
                    .key(key(subjectKey(fingerprint)))
                    .conditionExpression("deletion_id = :deletion AND user_id = :user")
                    .expressionAttributeValues(Map.of(":deletion", string(deletionId.toString()),
                            ":user", string(tombstone.userId().toString()))).build()).build());
        }
        deletes.add(TransactWriteItem.builder().delete(Delete.builder().tableName(tableName)
                .key(key(deletionKey(deletionId)))
                .conditionExpression("#state = :completed AND recoverable_point_count = :zero AND attribute_exists(last_recovery_check_at)")
                .expressionAttributeNames(Map.of("#state", "state"))
                .expressionAttributeValues(Map.of(":completed", string(AccountDeletionTombstone.State.COMPLETED.name()),
                        ":zero", number(0))).build()).build());
        dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(deletes).build());
    }

    @Override
    public void discardInProgress(UUID deletionId) {
        if (deletionId == null) return;
        List<Map<String, AttributeValue>> matches = new ArrayList<>();
        var page = dynamo.scan(ScanRequest.builder().tableName(tableName).consistentRead(true)
                .filterExpression("deletion_id = :deletion")
                .expressionAttributeValues(Map.of(":deletion", string(deletionId.toString()))).build());
        matches.addAll(page.items());
        while (page.lastEvaluatedKey() != null && !page.lastEvaluatedKey().isEmpty()) {
            page = dynamo.scan(ScanRequest.builder().tableName(tableName).consistentRead(true)
                    .exclusiveStartKey(page.lastEvaluatedKey()).filterExpression("deletion_id = :deletion")
                    .expressionAttributeValues(Map.of(":deletion", string(deletionId.toString()))).build());
            matches.addAll(page.items());
        }
        Map<String, AttributeValue> operation = matches.stream()
                .filter(match -> "DELETION".equals(value(match, "record_type"))).findFirst().orElse(null);
        if (operation != null && AccountDeletionTombstone.State.COMPLETED.name().equals(value(operation, "state"))) {
            return;
        }
        if (matches.size() > 100) throw new IllegalStateException("Uncommitted deletion fence exceeds atomic cleanup limit");
        List<TransactWriteItem> deletes = matches.stream().map(match -> TransactWriteItem.builder()
                .delete(Delete.builder().tableName(tableName)
                        .key(key(value(match, "record_id")))
                        .conditionExpression("deletion_id = :deletion AND (attribute_not_exists(#state) OR #state = :pending)")
                        .expressionAttributeNames(Map.of("#state", "state"))
                        .expressionAttributeValues(Map.of(":deletion", string(deletionId.toString()),
                                ":pending", string(AccountDeletionTombstone.State.IN_PROGRESS.name())))
                        .build()).build()).toList();
        if (!deletes.isEmpty()) dynamo.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(deletes).build());
    }

    private static Map<String, AttributeValue> subjectItem(UUID deletionId, UUID userId, String fingerprint) {
        return Map.of("record_id", string(subjectKey(fingerprint)),
                "record_type", string("SUBJECT"), "fingerprint", string(fingerprint),
                "deletion_id", string(deletionId.toString()), "user_id", string(userId.toString()));
    }

    private void requireSubjectFences(UUID deletionId, UUID userId, List<String> fingerprints) {
        for (String fingerprint : fingerprints) {
            Map<String, AttributeValue> subject = get(subjectKey(fingerprint))
                    .orElseThrow(() -> new IllegalStateException("Account deletion tombstone subject fence is missing"));
            if (!"SUBJECT".equals(value(subject, "record_type"))
                    || !deletionId.toString().equals(value(subject, "deletion_id"))
                    || !userId.toString().equals(value(subject, "user_id"))
                    || !fingerprint.equals(value(subject, "fingerprint"))) {
                throw new IllegalStateException("Account deletion tombstone subject fence conflicts");
            }
        }
    }

    private Optional<Map<String, AttributeValue>> get(String recordId) {
        return Optional.ofNullable(dynamo.getItem(GetItemRequest.builder().tableName(tableName)
                .key(key(recordId)).consistentRead(true).build()).item()).filter(item -> !item.isEmpty());
    }

    private void requireSameOperation(Map<String, AttributeValue> item, UUID deletionId, UUID userId,
                                      List<String> fingerprints) {
        AccountDeletionTombstone stored = toTombstone(item);
        if (!stored.deletionId().equals(deletionId) || !stored.userId().equals(userId)
                || !stored.sourceDbResourceId().equals(sourceDbResourceId)
                || !stored.identityFingerprints().equals(fingerprints)) {
            throw new IllegalStateException("Account deletion tombstone conflicts with the active deletion");
        }
    }

    private static AccountDeletionTombstone toTombstone(Map<String, AttributeValue> item) {
        AttributeValue fingerprintValue = item.get("fingerprints");
        List<String> fingerprints = fingerprintValue == null ? List.of() : fingerprintValue.ss().stream().sorted().toList();
        return new AccountDeletionTombstone(UUID.fromString(value(item, "deletion_id")),
                UUID.fromString(value(item, "user_id")), value(item, "source_db_resource_id"), fingerprints,
                AccountDeletionTombstone.State.valueOf(value(item, "state")),
                OffsetDateTime.parse(value(item, "fenced_at")), parseTime(item, "completed_at").orElse(null),
                parseTime(item, "last_recovery_check_at").orElse(null),
                parseTime(item, "oldest_recoverable_point_at").orElse(null),
                Integer.parseInt(item.getOrDefault("recoverable_point_count", number(0)).n()));
    }

    private static Map<String, AttributeValue> deletionItem(UUID deletionId, UUID userId, String sourceDbResourceId,
                                                            List<String> fingerprints,
                                                            OffsetDateTime fencedAt) {
        return Map.of("record_id", string(deletionKey(deletionId)), "record_type", string("DELETION"),
                "deletion_id", string(deletionId.toString()), "user_id", string(userId.toString()),
                "source_db_resource_id", string(sourceDbResourceId),
                "fingerprints", AttributeValue.builder().ss(fingerprints).build(), "state",
                string(AccountDeletionTombstone.State.IN_PROGRESS.name()), "fenced_at", string(fencedAt.toString()));
    }

    private static Optional<OffsetDateTime> parseTime(Map<String, AttributeValue> item, String name) {
        String value = value(item, name);
        return value == null ? Optional.empty() : Optional.of(OffsetDateTime.parse(value));
    }

    private static String value(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private static Map<String, AttributeValue> key(String value) {
        return Map.of("record_id", string(value));
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(int value) {
        return AttributeValue.builder().n(Integer.toString(value)).build();
    }

    private static String deletionKey(UUID deletionId) {
        return DELETION_PREFIX + deletionId;
    }

    private static String subjectKey(String fingerprint) {
        return SUBJECT_PREFIX + fingerprint;
    }

    private static void requireOperation(UUID deletionId, UUID userId) {
        if (deletionId == null || userId == null) throw new IllegalArgumentException("Deletion identity is required");
    }
}
