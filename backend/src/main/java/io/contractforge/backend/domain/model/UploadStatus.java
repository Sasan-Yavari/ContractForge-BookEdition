package io.contractforge.backend.domain.model;

/** Upload lifecycle values; EXPIRED is derived-only and REJECTED is persisted. */
public enum UploadStatus {
    UPLOADING,
    UPLOADED,
    SCANNED,
    PROMOTED,
    COMPLETED,
    REJECTED,
    EXPIRED;

    public String value() {
        return name();
    }
}
