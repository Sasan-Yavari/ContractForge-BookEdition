package io.contractforge.backend.application.processing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

public final class ProcessingFailureDiagnosticLogCapture implements AutoCloseable {
    private final Logger logger = (Logger) LoggerFactory.getLogger(ProcessingFailureDiagnostics.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public ProcessingFailureDiagnosticLogCapture() {
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);
    }

    public String field(String name) {
        return appender.list.stream().flatMap(event -> event.getKeyValuePairs().stream())
                .filter(pair -> pair.key.equals(name))
                .map(pair -> String.valueOf(pair.value))
                .findFirst().orElse(null);
    }

    public String renderedEvents() {
        return appender.list.stream().map(event -> event.getFormattedMessage() + " "
                + event.getKeyValuePairs().stream()
                        .map(pair -> pair.key + "=" + pair.value)
                        .collect(Collectors.joining(" ")))
                .collect(Collectors.joining("\n"));
    }

    public int eventCount() {
        return appender.list.size();
    }

    public boolean hasThrowable() {
        return appender.list.stream().anyMatch(event -> event.getThrowableProxy() != null);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
