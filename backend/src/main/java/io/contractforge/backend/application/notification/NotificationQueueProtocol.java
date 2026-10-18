package io.contractforge.backend.application.notification;

/** Canonical protocol values for the notification delivery queue. */
public final class NotificationQueueProtocol {
    public static final int SCHEMA_VERSION = 1;
    public static final String MESSAGE_TYPE_DELIVERY = "NOTIFICATION_DELIVERY";

    private NotificationQueueProtocol() {
    }
}
