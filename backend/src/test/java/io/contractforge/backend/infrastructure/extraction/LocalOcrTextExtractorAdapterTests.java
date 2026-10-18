package io.contractforge.backend.infrastructure.extraction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.contractforge.backend.application.extraction.OcrTextExtractorPort;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LocalOcrTextExtractorAdapterTests {
    @Test
    void rejectsOcrWithoutRecordingAProviderInvocation() {
        AtomicBoolean invoked = new AtomicBoolean();

        assertThrows(OcrTextExtractorPort.PermanentOcrException.class,
                () -> new LocalOcrTextExtractorAdapter().extract(new byte[] {1}, () -> invoked.set(true)));

        assertFalse(invoked.get());
    }
}
