package io.contractforge.backend.application.contract;

/** User-level lifecycle states exposed by the contract collection read model. */
public enum ContractCollectionStatus {
    NEEDS_REVIEW(0),
    COULD_NOT_PROCESS(0),
    NEEDS_ATTENTION(1),
    CONFIRMED(2),
    PROCESSING(3);

    private final int priority;

    ContractCollectionStatus(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
