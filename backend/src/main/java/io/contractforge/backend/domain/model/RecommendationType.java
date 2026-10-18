package io.contractforge.backend.domain.model;

/** Closed recommendation vocabulary. */
public enum RecommendationType {
    PRICE_OPPORTUNITY(null),
    CONTRACT_EXPIRY("contract expiry"),
    RENEWAL("renewal"),
    TERMS_CHANGE(null),
    DISCOUNT_EXPIRY(null),
    ACTION_REQUIRED(null);

    private final String displayName;

    RecommendationType(String displayName) {
        this.displayName = displayName;
    }

    public String value() {
        return name();
    }

    public String displayName() {
        if (displayName == null) {
            throw new IllegalStateException("Recommendation type has no renderer label: " + name());
        }
        return displayName;
    }

    public static RecommendationType fromValue(String value) {
        return valueOf(value);
    }
}
