package io.contractforge.backend.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class LocalPdfTextExtractorAdapterTests {
    @Test
    void preservesPageBoundariesForProviderNeutralPreparation() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String value : new String[] {"first page", "second page"}) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 720);
                    content.showText(value);
                    content.endText();
                }
            }
            document.save(output);
            pdf = output.toByteArray();
        }

        var result = new LocalPdfTextExtractorAdapter().extract(pdf);

        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.text()).contains("first page", "second page").contains("");
    }
}
