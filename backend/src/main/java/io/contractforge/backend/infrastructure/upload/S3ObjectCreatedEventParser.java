package io.contractforge.backend.infrastructure.upload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.upload.UploadIngestionService.ObjectCreatedCommand;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Decodes the direct S3-to-SQS notification envelope before translating it to the application command. */
final class S3ObjectCreatedEventParser {
    private static final String AWS_S3_EVENT_SOURCE = "aws:s3";
    private static final String OBJECT_CREATED_PREFIX = "ObjectCreated:";
    private static final String QUARANTINE_PREFIX = "quarantine/incoming/";

    private final ObjectMapper mapper;
    private final String expectedBucket;

    S3ObjectCreatedEventParser(ObjectMapper mapper, String expectedBucket) {
        this.mapper = mapper;
        this.expectedBucket = expectedBucket;
    }

    List<ObjectCreatedCommand> parse(String messageBody) {
        try {
            JsonNode envelope = mapper.readTree(messageBody);
            if (envelope != null && "s3:TestEvent".equals(envelope.path("Event").textValue())) {
                return List.of();
            }
            JsonNode records = envelope == null ? null : envelope.get("Records");
            if (records == null || !records.isArray() || records.isEmpty()) {
                throw malformed();
            }

            List<ObjectCreatedCommand> commands = new ArrayList<>(records.size());
            for (JsonNode record : records) {
                commands.add(parseRecord(record));
            }
            return List.copyOf(commands);
        } catch (JsonProcessingException | RuntimeException failure) {
            if (failure instanceof MalformedS3ObjectCreatedEventException malformed) {
                throw malformed;
            }
            throw new MalformedS3ObjectCreatedEventException(failure);
        }
    }

    private ObjectCreatedCommand parseRecord(JsonNode record) {
        if (!record.isObject() || !AWS_S3_EVENT_SOURCE.equals(text(record, "eventSource"))) {
            throw malformed();
        }
        String eventName = text(record, "eventName");
        if (eventName == null || !eventName.startsWith(OBJECT_CREATED_PREFIX)) {
            throw malformed();
        }

        JsonNode s3 = record.path("s3");
        String bucket = text(s3.path("bucket"), "name");
        String encodedKey = text(s3.path("object"), "key");
        if (!expectedBucket.equals(bucket) || encodedKey == null || encodedKey.isBlank()) {
            throw malformed();
        }

        String key = URLDecoder.decode(encodedKey, StandardCharsets.UTF_8);
        if (!key.startsWith(QUARANTINE_PREFIX)) {
            throw malformed();
        }
        String rawUploadId = key.substring(QUARANTINE_PREFIX.length());
        UUID uploadId;
        try {
            uploadId = UUID.fromString(rawUploadId);
        } catch (IllegalArgumentException failure) {
            throw new MalformedS3ObjectCreatedEventException(failure);
        }
        if (!uploadId.toString().equals(rawUploadId) || !key.equals(QUARANTINE_PREFIX + uploadId)) {
            throw malformed();
        }
        return new ObjectCreatedCommand(uploadId, key);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.textValue();
    }

    private MalformedS3ObjectCreatedEventException malformed() {
        return new MalformedS3ObjectCreatedEventException();
    }

    static final class MalformedS3ObjectCreatedEventException extends IllegalArgumentException {
        MalformedS3ObjectCreatedEventException() {
            super("Malformed S3 object-created notification");
        }

        MalformedS3ObjectCreatedEventException(Throwable cause) {
            super("Malformed S3 object-created notification", cause);
        }
    }
}
