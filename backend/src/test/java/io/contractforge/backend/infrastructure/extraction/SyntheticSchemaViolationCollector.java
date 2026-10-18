package io.contractforge.backend.infrastructure.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Local-only diagnostic traversal for wholly synthetic Bedrock probe responses.
 * Paths are assembled only from names in the trusted local schema.
 */
final class SyntheticSchemaViolationCollector {
    private SyntheticSchemaViolationCollector() { }

    static List<Violation> collect(JsonNode instance, JsonNode schema) {
        List<Violation> violations = new ArrayList<>();
        collect(instance, schema, "$", violations);
        return violations.stream().sorted(Comparator.comparing(Violation::path)
                .thenComparing(violation -> violation.rule().value())).toList();
    }

    private static void collect(JsonNode instance, JsonNode schema, String path, List<Violation> violations) {
        if (instance == null || schema == null) return;

        JsonNode constValue = schema.get("const");
        if (constValue != null && !constValue.equals(instance)) {
            add(violations, path, Rule.CONST_MISMATCH, expectedType(schema.get("type")), actualType(instance));
        }

        JsonNode enumValues = schema.get("enum");
        if (enumValues != null) {
            boolean matches = false;
            for (JsonNode value : enumValues) matches |= value.equals(instance);
            if (!matches) {
                add(violations, path, Rule.ENUM_MISMATCH, expectedType(schema.get("type")), actualType(instance));
            }
        }

        JsonNode type = schema.get("type");
        if (type != null && !matchesType(instance, type)) {
            add(violations, path, Rule.TYPE_MISMATCH, expectedType(type), actualType(instance));
        }

        if (instance.isObject()) {
            JsonNode required = schema.get("required");
            JsonNode properties = schema.path("properties");
            if (required != null && required.isArray()) {
                for (JsonNode requiredName : required) {
                    if (!requiredName.isTextual()) continue;
                    String safeName = schemaPropertyName(properties, requiredName.textValue());
                    if (safeName != null && !instance.has(safeName)) {
                        add(violations, childPath(path, safeName), Rule.REQUIRED_MISSING,
                                expectedType(properties.get(safeName).get("type")), JsonType.MISSING);
                    }
                }
            }

            var suppliedNames = instance.fieldNames();
            while (suppliedNames.hasNext()) {
                String suppliedName = suppliedNames.next();
                String safeName = schemaPropertyName(properties, suppliedName);
                if (safeName == null) {
                    if (schema.path("additionalProperties").isBoolean()
                            && !schema.path("additionalProperties").booleanValue()) {
                        // Deliberately collapse unexpected property names to the containing schema location.
                        add(violations, path, Rule.ADDITIONAL_PROPERTY, JsonType.OBJECT, JsonType.OBJECT);
                    }
                } else {
                    collect(instance.get(safeName), properties.get(safeName), childPath(path, safeName), violations);
                }
            }
        }

        if (instance.isArray() && schema.has("items")) {
            for (JsonNode item : instance) collect(item, schema.get("items"), path + "[*]", violations);
        }
    }

    private static String schemaPropertyName(JsonNode properties, String candidate) {
        if (properties == null || !properties.isObject()) return null;
        var names = properties.fieldNames();
        while (names.hasNext()) {
            String schemaName = names.next();
            if (schemaName.equals(candidate)) return schemaName;
        }
        return null;
    }

    private static String childPath(String parent, String trustedPropertyName) {
        return parent + "." + trustedPropertyName;
    }

    private static void add(List<Violation> violations, String path, Rule rule,
                            JsonType expected, JsonType actual) {
        violations.add(new Violation(path, rule, expected, actual));
    }

    private static boolean matchesType(JsonNode value, JsonNode type) {
        if (type.isArray()) {
            for (JsonNode alternative : type) if (matchesType(value, alternative)) return true;
            return false;
        }
        if (!type.isTextual()) return false;
        return switch (type.textValue()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> isInteger(value);
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static boolean isInteger(JsonNode value) {
        return value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0;
    }

    private static JsonType expectedType(JsonNode type) {
        if (type == null) return JsonType.UNKNOWN;
        if (type.isTextual()) return type(type.textValue());
        if (!type.isArray()) return JsonType.UNKNOWN;
        Set<String> alternatives = new HashSet<>();
        for (JsonNode alternative : type) {
            if (!alternative.isTextual()) return JsonType.UNKNOWN;
            alternatives.add(alternative.textValue());
        }
        if (alternatives.equals(Set.of("string", "null"))) return JsonType.STRING_OR_NULL;
        if (alternatives.equals(Set.of("integer", "null"))) return JsonType.INTEGER_OR_NULL;
        if (alternatives.equals(Set.of("number", "string", "null"))) return JsonType.NUMBER_STRING_OR_NULL;
        return JsonType.UNKNOWN;
    }

    private static JsonType type(String type) {
        return switch (type) {
            case "object" -> JsonType.OBJECT;
            case "array" -> JsonType.ARRAY;
            case "string" -> JsonType.STRING;
            case "integer" -> JsonType.INTEGER;
            case "number" -> JsonType.NUMBER;
            case "boolean" -> JsonType.BOOLEAN;
            case "null" -> JsonType.NULL;
            default -> JsonType.UNKNOWN;
        };
    }

    private static JsonType actualType(JsonNode value) {
        if (value.isObject()) return JsonType.OBJECT;
        if (value.isArray()) return JsonType.ARRAY;
        if (value.isTextual()) return JsonType.STRING;
        if (value.isNumber()) return JsonType.NUMBER;
        if (value.isBoolean()) return JsonType.BOOLEAN;
        if (value.isNull()) return JsonType.NULL;
        return JsonType.UNKNOWN;
    }

    record Violation(String path, Rule rule, JsonType expectedType, JsonType actualType) {
        String safeSummary() {
            return rule.value() + "|" + path + "|" + expectedType.value() + "|" + actualType.value();
        }
    }

    enum Rule {
        REQUIRED_MISSING("required_missing"),
        ADDITIONAL_PROPERTY("additional_property"),
        TYPE_MISMATCH("type_mismatch"),
        ENUM_MISMATCH("enum_mismatch"),
        CONST_MISMATCH("const_mismatch");

        private final String value;
        Rule(String value) { this.value = value; }
        String value() { return value; }
    }

    enum JsonType {
        OBJECT("object"),
        ARRAY("array"),
        STRING("string"),
        INTEGER("integer"),
        NUMBER("number"),
        BOOLEAN("boolean"),
        NULL("null"),
        MISSING("missing"),
        STRING_OR_NULL("string_or_null"),
        INTEGER_OR_NULL("integer_or_null"),
        NUMBER_STRING_OR_NULL("number_string_or_null"),
        UNKNOWN("unknown");

        private final String value;
        JsonType(String value) { this.value = value; }
        String value() { return value; }
    }
}
