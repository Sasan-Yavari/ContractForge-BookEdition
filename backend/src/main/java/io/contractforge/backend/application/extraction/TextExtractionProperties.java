package io.contractforge.backend.application.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "contractforge.extraction")
public record TextExtractionProperties(long maxInputBytes, int maxOutputCharacters,
                                       int minimumDirectTextCharacters, int maxPages,
                                       int maxLlmInputCharacters) {
    private static final long DEFAULT_MAX_INPUT_BYTES = 25 * 1024 * 1024L;
    private static final int DEFAULT_MAX_OUTPUT_CHARACTERS = 500_000;
    private static final int DEFAULT_MINIMUM_DIRECT_TEXT_CHARACTERS = 20;
    private static final int DEFAULT_MAX_PAGES = 100;
    private static final int DEFAULT_MAX_LLM_INPUT_CHARACTERS = 200_000;

    public TextExtractionProperties(long maxInputBytes, int maxOutputCharacters,
                                    int minimumDirectTextCharacters, int maxPages) {
        this(maxInputBytes, maxOutputCharacters, minimumDirectTextCharacters, maxPages, 0);
    }

    @ConstructorBinding
    public TextExtractionProperties {
        if (maxInputBytes == 0) maxInputBytes = DEFAULT_MAX_INPUT_BYTES;
        if (maxOutputCharacters == 0) maxOutputCharacters = DEFAULT_MAX_OUTPUT_CHARACTERS;
        if (minimumDirectTextCharacters == 0) minimumDirectTextCharacters = DEFAULT_MINIMUM_DIRECT_TEXT_CHARACTERS;
        if (maxPages == 0) maxPages = DEFAULT_MAX_PAGES;
        if (maxLlmInputCharacters == 0) maxLlmInputCharacters = DEFAULT_MAX_LLM_INPUT_CHARACTERS;
        if (maxInputBytes < 0 || maxOutputCharacters < 0 || minimumDirectTextCharacters < 0
                || maxPages < 0 || maxLlmInputCharacters < 0) {
            throw new IllegalArgumentException("Extraction limits must be positive");
        }
    }
}
