package io.contractforge.backend.application.accountdeletion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** SQS carries only an opaque durable deletion-job ID. */
@Component
public final class AccountDeletionEnvelopeSerializer {
    public static final int SCHEMA_VERSION = 1;
    public static final String MESSAGE_TYPE = "ACCOUNT_DELETION";

    private final ObjectMapper mapper;

    public AccountDeletionEnvelopeSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String serialize(UUID deletionId) {
        if (deletionId == null) throw new IllegalArgumentException("Account deletion ID is required");
        try {
            return mapper.writeValueAsString(new Envelope(SCHEMA_VERSION, MESSAGE_TYPE, deletionId));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to serialize account deletion message", failure);
        }
    }

    public UUID parse(String body) {
        try {
            JsonNode envelope = mapper.readTree(body);
            if (envelope == null || !envelope.isObject() || envelope.size() != 3
                    || !envelope.has("schemaVersion") || !envelope.get("schemaVersion").canConvertToInt()
                    || !envelope.has("messageType") || !envelope.get("messageType").isTextual()
                    || !envelope.has("deletionId") || !envelope.get("deletionId").isTextual()
                    || envelope.get("schemaVersion").intValue() != SCHEMA_VERSION
                    || !MESSAGE_TYPE.equals(envelope.get("messageType").textValue())) {
                throw new IllegalArgumentException("Unsupported account deletion message");
            }
            return UUID.fromString(envelope.get("deletionId").textValue());
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Malformed account deletion message", failure);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Malformed account deletion message", failure);
        }
    }

    private record Envelope(int schemaVersion, String messageType, UUID deletionId) { }
}
