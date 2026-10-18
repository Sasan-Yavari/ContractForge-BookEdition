package io.contractforge.backend.application.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.domain.model.ContractPartyRole;
import io.contractforge.backend.domain.model.ContractPricingModel;
import io.contractforge.backend.domain.model.ContractRenewalType;
import io.contractforge.backend.domain.model.DiscountType;
import io.contractforge.backend.domain.model.FixedChargeType;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** Applies deterministic schema and business rules before any candidate is persisted. */
public final class ElectricityContractCandidateValidator {
    private static final Set<String> PARTY_ROLES = values(ContractPartyRole.values());
    private static final Set<String> RENEWAL_TYPES = values(ContractRenewalType.values());
    private static final Set<String> PRICING_MODELS = values(ContractPricingModel.values());
    private static final Set<String> ELECTRICITY_FIELDS = Set.of(
            "consumption", "pricing", "fixed_charges", "return_delivery", "discounts");
    private static final Set<String> CONSUMPTION_FIELDS = Set.of("annual_kwh");
    private static final Set<String> PRICING_FIELDS = Set.of("model", "rates", "index");
    private static final Set<String> RATE_FIELDS = Set.of("value", "unit", "currency", "period", "time_period", "index");
    private static final Set<String> CHARGE_FIELDS = Set.of("type", "amount", "currency", "period");
    private static final Set<String> RETURN_DELIVERY_FIELDS = Set.of("compensation", "fees");
    private static final Set<String> DISCOUNT_FIELDS = Set.of("type", "amount", "value", "currency", "period");
    private final ObjectMapper json;

    public ElectricityContractCandidateValidator(ObjectMapper json) {
        this.json = json;
    }

    public void validate(ContractExtractionPort.Candidate candidate) {
        if (candidate == null || !ContractExtractionPort.CURRENT_SCHEMA_VERSION.equals(candidate.schemaVersion())
                || candidate.electricityData() == null) {
            throw new InvalidCandidateException();
        }
        validateParties(candidate);
        validateDates(candidate.startDate(), candidate.endDate(), candidate.durationMonths());
        if (candidate.renewalType() != null && !RENEWAL_TYPES.contains(candidate.renewalType())) {
            throw new InvalidCandidateException();
        }
        if (candidate.pricingModel() != null && !PRICING_MODELS.contains(candidate.pricingModel())) {
            throw new InvalidCandidateException();
        }
        validateElectricityData(candidate.electricityData().value());
    }

    private void validateParties(ContractExtractionPort.Candidate candidate) {
        if (candidate.parties().isEmpty()) throw new InvalidCandidateException();
        Set<String> unique = new HashSet<>();
        for (ContractExtractionPort.Party party : candidate.parties()) {
            if (party == null || party.name() == null || party.name().isBlank() || party.name().length() > 500
                    || !PARTY_ROLES.contains(party.role()) || !unique.add(party.role() + "\u0000" + party.name())) {
                throw new InvalidCandidateException();
            }
        }
    }

    private void validateDates(LocalDate startDate, LocalDate endDate, Integer durationMonths) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new InvalidCandidateException();
        }
        if (durationMonths != null && durationMonths <= 0) {
            throw new InvalidCandidateException();
        }
    }

    private void validateElectricityData(String value) {
        try {
            JsonNode root = json.readTree(value);
            if (root == null || !root.isObject()) throw new InvalidCandidateException();
            var fields = root.fieldNames();
            while (fields.hasNext()) {
                if (!ELECTRICITY_FIELDS.contains(fields.next())) throw new InvalidCandidateException();
            }
            validateObjectFields(root, "consumption", CONSUMPTION_FIELDS);
            validateObjectFields(root, "pricing", PRICING_FIELDS);
            validateArrayFields(root, "pricing", "rates", RATE_FIELDS);
            validateArrayFields(root, null, "fixed_charges", CHARGE_FIELDS);
            validateObjectFields(root, "return_delivery", RETURN_DELIVERY_FIELDS);
            validateArrayFields(root, null, "discounts", DISCOUNT_FIELDS);
            validateValues(root);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new InvalidCandidateException(failure);
        }
    }

    private void validateValues(JsonNode root) {
        JsonNode consumption = root.get("consumption");
        if (consumption != null && !consumption.isNull()) validateNumber(consumption.get("annual_kwh"));
        JsonNode pricing = root.get("pricing");
        if (pricing != null && !pricing.isNull()) {
            JsonNode model = pricing.get("model");
            if (model != null && !model.isNull() && (!model.isTextual() || !PRICING_MODELS.contains(model.textValue()))) {
                throw new InvalidCandidateException();
            }
            validateText(pricing.get("index"));
            validateNumericArray(pricing.get("rates"), "value");
            validateRateTypes(pricing.get("rates"));
        }
        validateChargeTypes(root.get("fixed_charges"));
        JsonNode returnDelivery = root.get("return_delivery");
        if (returnDelivery != null && !returnDelivery.isNull()) {
            validateNumberOrText(returnDelivery.get("compensation"));
            validateNumberOrText(returnDelivery.get("fees"));
        }
        JsonNode discounts = root.get("discounts");
        if (discounts != null && !discounts.isNull()) {
            for (JsonNode discount : array(discounts)) {
                JsonNode type = discount.get("type");
                if (type != null && !type.isNull() && (!type.isTextual()
                        || !values(DiscountType.values()).contains(type.textValue()))) {
                    throw new InvalidCandidateException();
                }
                validateNumber(discount.get("amount"));
                validateNumber(discount.get("value"));
                validateText(discount.get("currency"));
                validateText(discount.get("period"));
            }
        }
    }

    private void validateRateTypes(JsonNode rates) {
        if (rates == null || rates.isNull()) return;
        for (JsonNode rate : array(rates)) {
            validateText(rate.get("unit"));
            validateText(rate.get("currency"));
            validateText(rate.get("period"));
            validateText(rate.get("time_period"));
            validateText(rate.get("index"));
        }
    }

    private void validateChargeTypes(JsonNode charges) {
        if (charges == null || charges.isNull()) return;
        for (JsonNode charge : array(charges)) {
            JsonNode type = charge.get("type");
            if (type != null && !type.isNull() && (!type.isTextual()
                    || !values(FixedChargeType.values()).contains(type.textValue()))) {
                throw new InvalidCandidateException();
            }
            validateNumber(charge.get("amount"));
            validateText(charge.get("currency"));
            validateText(charge.get("period"));
        }
    }

    private void validateNumericArray(JsonNode array, String field) {
        if (array == null || array.isNull()) return;
        for (JsonNode item : array(array)) {
            validateNumber(item.get(field));
        }
    }

    private Iterable<JsonNode> array(JsonNode node) {
        if (!node.isArray()) throw new InvalidCandidateException();
        return node;
    }

    private static <E extends Enum<E>> Set<String> values(E[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void validateNumber(JsonNode node) {
        if (node != null && !node.isNull() && !node.isNumber()) throw new InvalidCandidateException();
        if (node != null && node.isNumber()) rejectNegative(node);
    }

    private void validateNumberOrText(JsonNode node) {
        if (node != null && !node.isNull() && !node.isNumber() && !node.isTextual()) {
            throw new InvalidCandidateException();
        }
    }

    private void validateText(JsonNode node) {
        if (node != null && !node.isNull() && !node.isTextual()) throw new InvalidCandidateException();
    }

    private void rejectNegative(JsonNode node) {
        if (node.isNumber() && node.decimalValue().signum() < 0) throw new InvalidCandidateException();
    }

    private void validateObjectFields(JsonNode parent, String objectField, Set<String> allowed) {
        JsonNode object = parent.get(objectField);
        if (object == null || object.isNull()) return;
        if (!object.isObject()) throw new InvalidCandidateException();
        rejectUnknownFields(object, allowed);
    }

    private void validateArrayFields(JsonNode parent, String objectField, String arrayField, Set<String> allowed) {
        JsonNode container = objectField == null ? parent : parent.get(objectField);
        if (container == null || container.isNull()) return;
        JsonNode array = container.get(arrayField);
        if (array == null || array.isNull()) return;
        if (!array.isArray()) throw new InvalidCandidateException();
        for (JsonNode item : array) {
            if (!item.isObject()) throw new InvalidCandidateException();
            rejectUnknownFields(item, allowed);
        }
    }

    private void rejectUnknownFields(JsonNode object, Set<String> allowed) {
        var fields = object.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) throw new InvalidCandidateException();
        }
    }

    public static class InvalidCandidateException extends RuntimeException {
        public InvalidCandidateException() { }
        public InvalidCandidateException(Throwable cause) { super(cause); }
    }
}
