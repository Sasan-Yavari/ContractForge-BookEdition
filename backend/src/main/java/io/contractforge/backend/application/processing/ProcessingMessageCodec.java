package io.contractforge.backend.application.processing;

import io.contractforge.backend.application.port.ProcessingPublisherPort.ProcessingMessage;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict codec for the small server-created processing message contract. */
public final class ProcessingMessageCodec {
    private static final Pattern DOCUMENT_ID = Pattern.compile("\\A\\{\\\"documentId\\\":\\\"([0-9a-fA-F-]{36})\\\"}\\z");

    private ProcessingMessageCodec() { }

    public static ProcessingMessage decode(String body) {
        if (body == null) {
            throw new MalformedProcessingMessageException();
        }
        Matcher matcher = DOCUMENT_ID.matcher(body);
        if (!matcher.matches()) {
            throw new MalformedProcessingMessageException();
        }
        try {
            return new ProcessingMessage(UUID.fromString(matcher.group(1)));
        } catch (IllegalArgumentException exception) {
            throw new MalformedProcessingMessageException();
        }
    }

    public static class MalformedProcessingMessageException extends RuntimeException { }
}
