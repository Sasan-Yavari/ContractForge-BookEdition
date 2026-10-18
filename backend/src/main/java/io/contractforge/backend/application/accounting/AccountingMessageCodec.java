package io.contractforge.backend.application.accounting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.UsageCostStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Strict JSON boundary for usage messages; document/provider content is never logged. */
public final class AccountingMessageCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private AccountingMessageCodec() { }

    public static String encode(AccountingPort.UsageEvent event) {
        try {
            return MAPPER.writeValueAsString(new Message(event.usageEventId(), event.userId(), event.documentId(),
                    event.operation(), event.provider(), event.service(), MAPPER.readTree(event.usageData().value()),
                    event.costAmount(), event.currency(), event.occurredAt().toString(), event.costStatus(), event.pricingVersion()));
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new IllegalArgumentException("Usage event cannot be encoded", failure);
        }
    }

    public static AccountingPort.UsageEvent decode(String body) {
        try {
            Message message = MAPPER.readValue(body, Message.class);
            if (message.usageData() == null || message.usageData().isNull()) throw new IllegalArgumentException("Missing usageData");
            return new AccountingPort.UsageEvent(message.usageEventId(), message.userId(), message.documentId(),
                    required(message.operation(), "operation"), required(message.provider(), "provider"),
                    required(message.service(), "service"), new JsonValue(message.usageData().toString()),
                    message.costAmount(), required(message.currency(), "currency"),
                    OffsetDateTime.parse(required(message.occurredAt(), "occurredAt")),
                    message.costStatus() == null ? UsageCostStatus.LEGACY : message.costStatus(), message.pricingVersion());
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new MalformedAccountingMessageException(failure);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    private record Message(UUID usageEventId, UUID userId, UUID documentId, String operation,
                           String provider, String service, com.fasterxml.jackson.databind.JsonNode usageData,
                           BigDecimal costAmount, String currency, String occurredAt,
                           UsageCostStatus costStatus, String pricingVersion) { }

    public static final class MalformedAccountingMessageException extends RuntimeException {
        public MalformedAccountingMessageException(Throwable cause) { super(cause); }
    }
}
