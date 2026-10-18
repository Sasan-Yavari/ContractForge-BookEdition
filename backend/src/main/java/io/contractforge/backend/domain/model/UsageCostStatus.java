package io.contractforge.backend.domain.model;

/** Explicitly distinguishes a calculated monetary amount from an unknown amount. */
public enum UsageCostStatus {
    CALCULATED,
    UNKNOWN,
    LEGACY
}
