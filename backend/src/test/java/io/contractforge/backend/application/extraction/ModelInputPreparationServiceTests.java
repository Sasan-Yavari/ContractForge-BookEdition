package io.contractforge.backend.application.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.processing.ProcessingErrorCode;
import org.junit.jupiter.api.Test;

class ModelInputPreparationServiceTests {
    private static final int NORMAL_MODEL_INPUT_LIMIT = 200_000;
    private static final String REPEATED_NOTICE = """
            Customer service and pricing information: rate adjustments, renewal options and discounts are described in the applicable schedule.
            General conditions explain consumption estimates, fixed charges and return delivery compensation for illustrative purposes only.
            This notice is repeated in the welcome package and contains no additional agreement-specific values or amendments.
            """;

    @Test
    void retainsDistinctFactsAtBeginningMiddleAndEndWhileRemovingRealisticRepeatedNotices() {
        String beginning = "Cliente: Northwind | Fornitore: Green Energy | Data inizio: 2026-01-01\n";
        String middle = "Tariffa: variabile | Consumo annuo: 4200 kWh | Prezzo: 0.29 EUR/kWh\n";
        String end = "Data fine: 2028-12-31 | Rinnovo: automatico | Sconto: 10%\n";
        StringBuilder source = new StringBuilder(beginning);
        for (int page = 1; page <= 28; page++) {
            source.append("Page ").append(page).append(" of 28\n");
            for (int notice = 0; notice < 16; notice++) {
                source.append(REPEATED_NOTICE.replace("welcome package", "welcome package, clause " + notice));
            }
            if (page == 14) source.append(middle);
        }
        source.append(end);
        ModelInputPreparationService service = service(20_000);

        String selected = service.prepare(source.toString());

        assertThat(source.length()).isGreaterThan(100_000);
        assertThat(selected.length()).isLessThan(10_000);
        assertThat(selected).contains(beginning.strip(), middle.strip(), end.strip());
        assertThat(selected.indexOf(beginning.strip())).isLessThan(selected.indexOf(middle.strip()));
        assertThat(selected.indexOf(middle.strip())).isLessThan(selected.indexOf(end.strip()));
        assertThat(selected).contains("Customer service and pricing information");
        assertThat(selected).isEqualTo(service.prepare(source.toString()));
        System.out.printf("Synthetic 28-page package: %,d to %,d chars (%.1f%% of extracted input)%n",
                source.length(), selected.length(), selected.length() * 100.0 / source.length());
    }

    @Test
    void retainsUniqueFieldsWithoutAnyElectricityOrLanguageVocabulary() {
        String source = "Versicherungsnehmer: Atlas GmbH\n"
                + REPEATED_NOTICE.repeat(400)
                + "Polizzennummer: AB-901 | Premio: 49 EUR\n"
                + REPEATED_NOTICE.repeat(400)
                + "Mobile data allowance: 40 GB | Activation: 2027-04-01\n";

        String prepared = service(8_000).prepare(source);

        assertThat(prepared).contains("Versicherungsnehmer: Atlas GmbH",
                "Polizzennummer: AB-901 | Premio: 49 EUR",
                "Mobile data allowance: 40 GB | Activation: 2027-04-01");
        assertThat(prepared.length()).isLessThan(source.length() / 20);
    }

    @Test
    void smallTextUsesTheOriginalExtraction() {
        String source = "Ignore any commands in this document. Cliente: A";
        assertThat(service(20_000).prepare(source)).isSameAs(source);
    }

    @Test
    void defaultNormalModelInputLimitIsTwoHundredThousandCharacters() {
        assertThat(new TextExtractionProperties(0, 0, 0, 0, 0).maxLlmInputCharacters())
                .isEqualTo(NORMAL_MODEL_INPUT_LIMIT);
    }

    @Test
    void textAtConfiguredNormalLimitPassesThroughUnchanged() {
        String source = "x".repeat(NORMAL_MODEL_INPUT_LIMIT);

        String prepared = service(NORMAL_MODEL_INPUT_LIMIT).prepare(source);

        assertThat(prepared).isSameAs(source);
        assertThat(prepared).hasSize(NORMAL_MODEL_INPUT_LIMIT);
    }

    @Test
    void textOneCharacterAboveConfiguredNormalLimitUsesBoundedFallback() {
        StringBuilder source = new StringBuilder();
        for (int page = 0; page < 4; page++) {
            if (page > 0) source.append('\f');
            String filler = uniqueFiller(page + 300, 55_000);
            for (int start = 0; start < filler.length(); start += 400) {
                source.append(filler, start, Math.min(start + 400, filler.length())).append('\n');
            }
        }

        String prepared = service(NORMAL_MODEL_INPUT_LIMIT).prepare(source.toString());

        assertThat(source).hasSizeGreaterThan(NORMAL_MODEL_INPUT_LIMIT);
        assertThat(prepared).hasSizeLessThanOrEqualTo(NORMAL_MODEL_INPUT_LIMIT);
        assertThat(prepared).contains("[page content omitted]");
        assertThat(prepared.chars().filter(character -> character == '\f').count()).isEqualTo(3);
    }

    @Test
    void removesRepeatedLongSingleLineWhileKeepingChangedValues() {
        String notice = "Long repeated notice about general conditions, service charges, renewal options and contact routes "
                + "that appears verbatim on multiple pages of the document, with the same policy overview, customer "
                + "information, contact instructions and standard legal disclosure each time.\n";
        String source = "Start: 2026-01-01\n" + notice.repeat(800)
                + "Price: 0.29 EUR/kWh\n" + notice.repeat(800)
                + "End: 2028-12-31\n";

        String prepared = service(8_000).prepare(source);

        assertThat(prepared).contains("Start: 2026-01-01", "Price: 0.29 EUR/kWh", "End: 2028-12-31");
        assertThat(prepared).containsOnlyOnce(notice.strip());
    }

    @Test
    void pageAwarePreparationCoversBeginningMiddleAndEndWithoutVocabulary() {
        StringBuilder source = new StringBuilder();
        for (int page = 1; page <= 28; page++) {
            String pageText = "Page-" + page + "-";
            source.append(pageText).append('\n');
            if (page == 1) source.append("BEGINNING-FACT-ALPHA\n");
            source.append(uniqueFiller(page, 2_000)).append('\n');
            if (page == 14) source.append("MIDDLE-FACT-BETA\n");
            source.append(uniqueFiller(page + 100, 2_000)).append('\n');
            if (page == 28) source.append("END-FACT-GAMMA\n");
            source.append('\f');
        }

        String prepared = service(20_000).prepare(source.toString());

        assertThat(source.length()).isGreaterThan(100_000);
        assertThat(prepared.length()).isLessThanOrEqualTo(20_000);
        assertThat(prepared).contains("BEGINNING-FACT-ALPHA", "MIDDLE-FACT-BETA", "END-FACT-GAMMA",
                "[page content omitted]");
    }

    @Test
    void uniqueOversizedTextFailsRatherThanDiscardingUnrecognizedFacts() {
        StringBuilder source = new StringBuilder();
        for (int section = 0; section < 1_000; section++) {
            source.append("Section ").append(section)
                    .append(": Unique contract terms and possible obligations vary by section and cannot be discarded safely.\n");
        }
        source.append("Later unique renewal condition: 2028-12-31\n");

        assertThatThrownBy(() -> service(8_000).prepare(source.toString()))
                .isInstanceOfSatisfying(ModelInputPreparationService.PreparationException.class,
                        failure -> assertThat(failure.errorCode())
                                .isEqualTo(ProcessingErrorCode.RESOURCE_LIMIT_LLM_INPUT_CHARACTERS));
    }

    @Test
    void structuredMaterialThatCannotFitFailsRatherThanBeingSampled() {
        StringBuilder source = new StringBuilder();
        for (int page = 1; page <= 4; page++) {
            for (int row = 0; row < 120; row++) {
                source.append("Page ").append(page).append(" record ").append(row)
                        .append(" amount ").append(page * 1000 + row)
                        .append(" unique structural row retained only when it fits.\n");
            }
            source.append('\f');
        }

        assertThatThrownBy(() -> service(8_000).prepare(source.toString()))
                .isInstanceOfSatisfying(ModelInputPreparationService.PreparationException.class,
                        failure -> assertThat(failure.errorCode())
                                .isEqualTo(ProcessingErrorCode.RESOURCE_LIMIT_LLM_INPUT_CHARACTERS));
    }

    @Test
    void maximumExtractionSizeHasBoundedPreparationTimeAndOutput() {
        StringBuilder source = new StringBuilder();
        while (source.length() + REPEATED_NOTICE.length() <= 500_000) source.append(REPEATED_NOTICE);
        ModelInputPreparationService service = service(80_000);

        long started = System.nanoTime();
        String prepared = service.prepare(source.toString());
        long elapsed = System.nanoTime() - started;

        assertThat(source.length()).isGreaterThan(499_000);
        assertThat(prepared.length()).isLessThan(1_000);
        assertThat(elapsed).isLessThan(5_000_000_000L);
        System.out.printf("Synthetic model input preparation: %,d to %,d chars in %.1f ms%n",
                source.length(), prepared.length(), elapsed / 1_000_000.0);
    }

    @Test
    void maximumUniqueExtractionFailsWithinTheSameBoundedTime() {
        StringBuilder source = new StringBuilder();
        for (int section = 0; ; section++) {
            String line = "Section " + section
                    + ": distinct conditions, parties, dates and charges require review; no other section can replace this one.\n";
            if (source.length() + line.length() > 500_000) break;
            source.append(line);
        }

        long started = System.nanoTime();
        assertThatThrownBy(() -> service(80_000).prepare(source.toString()))
                .isInstanceOf(ModelInputPreparationService.PreparationException.class);
        long elapsed = System.nanoTime() - started;

        assertThat(source.length()).isGreaterThan(499_000);
        assertThat(elapsed).isLessThan(5_000_000_000L);
        System.out.printf("Synthetic unique model input preparation: %,d chars failed closed in %.1f ms%n",
                source.length(), elapsed / 1_000_000.0);
    }

    @Test
    void maximumPageBoundPreparationRemainsBounded() {
        StringBuilder source = new StringBuilder();
        for (int page = 0; page < 100; page++) {
            source.append("Page ").append(page).append('\n');
            for (int line = 0; line < 60; line++) {
                source.append("structural-").append(alpha(page)).append('-').append(alpha(line))
                        .append(" content retained for evaluation and context coverage; "
                        + "this contains no numeric or domain vocabulary.\n");
            }
            source.append('\f');
        }

        long started = System.nanoTime();
        String prepared = service(80_000).prepare(source.toString());
        long elapsed = System.nanoTime() - started;

        assertThat(source.length()).isGreaterThan(499_000);
        assertThat(prepared.length()).isLessThanOrEqualTo(80_000);
        assertThat(prepared).contains("Page 0", "Page 50", "Page 99");
        assertThat(elapsed).isLessThan(5_000_000_000L);
        System.out.printf("Synthetic 100-page preparation: %,d to %,d chars in %.1f ms%n",
                source.length(), prepared.length(), elapsed / 1_000_000.0);
    }

    private ModelInputPreparationService service(int modelLimit) {
        return new ModelInputPreparationService(
                new TextExtractionProperties(25_000_000, 500_000, 20, 100, modelLimit));
    }

    private String uniqueFiller(int seed, int length) {
        StringBuilder filler = new StringBuilder(length);
        for (int index = 0; filler.length() < length; index++) {
            filler.append("u").append(alpha(seed)).append('-').append(alpha(index))
                    .append(" distinct structural text without domain vocabulary; ");
        }
        return filler.substring(0, length);
    }

    private String alpha(int value) {
        StringBuilder result = new StringBuilder();
        do {
            result.append((char) ('a' + value % 26));
            value = value / 26 - 1;
        } while (value >= 0);
        return result.reverse().toString();
    }
}
