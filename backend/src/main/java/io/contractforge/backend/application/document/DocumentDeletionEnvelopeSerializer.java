package io.contractforge.backend.application.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public final class DocumentDeletionEnvelopeSerializer {
    public static final int SCHEMA_VERSION = 1;
    public static final String MESSAGE_TYPE = "DOCUMENT_S3_DELETION";
    private final ObjectMapper mapper;

    public DocumentDeletionEnvelopeSerializer(ObjectMapper mapper) { this.mapper = mapper; }

    public String serialize(DocumentDeletionQueuePort.DocumentDeletionMessage message) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("messageType", MESSAGE_TYPE);
        envelope.put("storageKey", message.storageKey());
        try { return mapper.writeValueAsString(envelope); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot serialize deletion message", failure); }
    }
}
