package io.contractforge.backend.infrastructure.extraction;

import io.contractforge.backend.application.extraction.DirectTextExtractorPort;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** PDFBox adapter for deterministic local extraction; parser failures fail closed. */
@Component
@Profile("!test")
final class LocalPdfTextExtractorAdapter implements DirectTextExtractorPort {
    @Override
    public ExtractionResult extract(byte[] document) {
        try (var pdf = Loader.loadPDF(document)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setPageStart("\f");
            return new ExtractionResult(stripper.getText(pdf), pdf.getNumberOfPages());
        } catch (IOException | RuntimeException failure) {
            throw new DirectTextExtractorPort.MalformedDocumentException(failure);
        }
    }
}
