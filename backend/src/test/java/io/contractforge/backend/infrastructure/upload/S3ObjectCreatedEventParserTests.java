package io.contractforge.backend.infrastructure.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class S3ObjectCreatedEventParserTests {
    private static final UUID UPLOAD_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final String KEY = "quarantine/incoming/" + UPLOAD_ID;
    private final S3ObjectCreatedEventParser parser = new S3ObjectCreatedEventParser(new ObjectMapper(), "documents-dev");

    @Test
    void parsesOnlyBucketAndGeneratedUploadKeyAndIgnoresEventIdentityClaims() {
        var commands = parser.parse(event("documents-dev", "ObjectCreated:Put", KEY));

        assertThat(commands).containsExactly(new io.contractforge.backend.application.upload.UploadIngestionService
                .ObjectCreatedCommand(UPLOAD_ID, KEY));
    }

    @Test
    void decodesS3FormEncodedObjectKeys() {
        assertThat(parser.parse(event("documents-dev", "ObjectCreated:Put", KEY.replace("/", "%2F"))))
                .hasSize(1);
    }

    @Test
    void rejectsWrongBucketUnexpectedPrefixAndNonUuidKey() {
        assertMalformed(event("documents-prod", "ObjectCreated:Put", KEY));
        assertMalformed(event("documents-dev", "ObjectCreated:Put", "trusted/" + UPLOAD_ID));
        assertMalformed(event("documents-dev", "ObjectCreated:Put", "quarantine/incoming/not-an-id"));
    }

    @Test
    void rejectsNonObjectCreatedAndMalformedS3Envelopes() {
        assertMalformed(event("documents-dev", "ObjectRemoved:Delete", KEY));
        assertMalformed("{\"Records\":[]}");
        assertMalformed("not-json");
    }

    @Test
    void ignoresS3NotificationValidationTestEvent() {
        assertThat(parser.parse("{\"Service\":\"Amazon S3\",\"Event\":\"s3:TestEvent\"}"))
                .isEmpty();
    }

    private void assertMalformed(String body) {
        assertThatThrownBy(() -> parser.parse(body))
                .isInstanceOf(S3ObjectCreatedEventParser.MalformedS3ObjectCreatedEventException.class);
    }

    private String event(String bucket, String eventName, String key) {
        return "{\"Records\":[{\"eventSource\":\"aws:s3\",\"eventName\":\"" + eventName
                + "\",\"userIdentity\":{\"principalId\":\"untrusted-event-identity\"}"
                + ",\"s3\":{\"bucket\":{\"name\":\"" + bucket
                + "\"},\"object\":{\"key\":\"" + key + "\"}}}]}";
    }
}
