package io.contractforge.backend.application.extraction;

import io.contractforge.backend.application.processing.PermanentProcessingException;
import io.contractforge.backend.application.processing.ProcessingErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Removes exact repeats and creates a page-stratified model input for oversized PDF text.
 */
@Service
@Profile("!test")
public class ModelInputPreparationService {
    private static final int MIN_REPEATED_CHARACTERS = 200;
    private static final int REPEATED_LINE_COUNT = 3;
    private static final String PAGE_SEPARATOR = "\f";
    private static final String PAGE_OMISSION_MARKER = "\n[page content omitted]\n";
    private static final long HASH_OFFSET = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;

    private final TextExtractionProperties properties;

    public ModelInputPreparationService(TextExtractionProperties properties) {
        this.properties = properties;
    }

    public String prepare(String extractedText) {
        if (extractedText == null || extractedText.isBlank()) throw new PreparationException();
        int limit = properties.maxLlmInputCharacters();
        if (extractedText.length() <= limit) return extractedText;

        int[] lineEnds = lineEnds(extractedText);
        Map<Long, Integer> emittedBlocks = new HashMap<>();
        StringBuilder prepared = new StringBuilder(Math.min(limit, 8_192));
        int consecutiveEmittedLines = 0;
        for (int line = 0; line < lineEnds.length;) {
            int start = line == 0 ? 0 : lineEnds[line - 1];
            int repeatedLines = repeatedLines(extractedText, lineEnds, line, start, emittedBlocks);
            if (repeatedLines > 0) {
                line += repeatedLines;
                consecutiveEmittedLines = 0;
                continue;
            }
            int end = lineEnds[line];
            if (prepared.length() + end - start > limit) return preparePages(extractedText, limit);
            prepared.append(extractedText, start, end);
            if (end - start >= MIN_REPEATED_CHARACTERS) remember(extractedText, start, end, emittedBlocks);
            if (++consecutiveEmittedLines >= REPEATED_LINE_COUNT) {
                int blockStart = line == REPEATED_LINE_COUNT - 1
                        ? 0 : lineEnds[line - REPEATED_LINE_COUNT];
                if (end - blockStart >= MIN_REPEATED_CHARACTERS) {
                    remember(extractedText, blockStart, end, emittedBlocks);
                }
            }
            line++;
        }
        String result = prepared.toString();
        if (result.isBlank()) throw new PreparationException();
        return result;
    }

    private String preparePages(String text, int limit) {
        List<Page> pages = pages(text);
        if (pages.size() < 2) throw new PreparationException();
        List<List<Line>> pageLines = pages.stream()
                .map(page -> lines(text, page.start(), page.end())).toList();
        int structuredCharacters = pageLines.stream().flatMap(List::stream)
                .filter(Line::structured).mapToInt(Line::length).sum();
        int reserved = (pages.size() - 1) * PAGE_SEPARATOR.length()
                + pages.size() * PAGE_OMISSION_MARKER.length();
        if (structuredCharacters > limit - reserved) throw new PreparationException();
        int contextBudget = limit - reserved - structuredCharacters;

        StringBuilder result = new StringBuilder(limit);
        int contextPerPage = contextBudget / pages.size();
        for (int page = 0; page < pages.size(); page++) {
            if (page > 0) result.append(PAGE_SEPARATOR);
            appendPageLines(result, text, pageLines.get(page), contextPerPage);
        }
        if (result.length() > limit) throw new PreparationException();
        return result.toString();
    }

    private void appendPageLines(StringBuilder result, String text, List<Line> lines, int contextBudget) {
        boolean[] selected = new boolean[lines.size()];
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).structured()) selected[index] = true;
        }
        List<Integer> context = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            if (!selected[index]) context.add(index);
        }
        int windowBudget = Math.max(1, contextBudget / 3);
        selectContext(lines, selected, context, 0, 1, windowBudget);
        selectContext(lines, selected, context, context.size() / 2, 1, windowBudget);
        selectContext(lines, selected, context, context.size() - 1, -1, windowBudget);

        boolean omitted = false;
        boolean markerWritten = false;
        for (int index = 0; index < lines.size(); index++) {
            if (!selected[index]) {
                omitted = true;
                continue;
            }
            if (omitted && !markerWritten) {
                result.append(PAGE_OMISSION_MARKER);
                markerWritten = true;
            }
            Line line = lines.get(index);
            result.append(text, line.start(), line.end());
        }
    }

    private void selectContext(List<Line> lines, boolean[] selected, List<Integer> context,
                               int start, int direction, int budget) {
        int used = 0;
        for (int offset = start; offset >= 0 && offset < context.size(); offset += direction) {
            int index = context.get(offset);
            int length = lines.get(index).length();
            if (used + length > budget) break;
            selected[index] = true;
            used += length;
        }
    }


    private List<Page> pages(String text) {
        List<Page> pages = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int separator = text.indexOf(PAGE_SEPARATOR, start);
            int end = separator < 0 ? text.length() : separator;
            if (end > start) pages.add(new Page(start, end));
            start = separator < 0 ? text.length() : separator + PAGE_SEPARATOR.length();
        }
        return pages;
    }

    private List<Line> lines(String text, int start, int end) {
        List<Line> lines = new ArrayList<>();
        int lineStart = start;
        while (lineStart < end) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = newline < 0 || newline >= end ? end : newline + 1;
            lines.add(new Line(lineStart, lineEnd, hasDigit(text, lineStart, lineEnd)));
            lineStart = lineEnd;
        }
        return lines;
    }

    private boolean hasDigit(String text, int start, int end) {
        for (int index = start; index < end; index++) {
            if (Character.isDigit(text.charAt(index))) return true;
        }
        return false;
    }

    private int repeatedLines(String text, int[] lineEnds, int line, int start,
                              Map<Long, Integer> emittedBlocks) {
        if (line + REPEATED_LINE_COUNT <= lineEnds.length) {
            int end = lineEnds[line + REPEATED_LINE_COUNT - 1];
            if (end - start >= MIN_REPEATED_CHARACTERS && isRepeat(text, start, end, emittedBlocks)) {
                return REPEATED_LINE_COUNT;
            }
        }
        int end = lineEnds[line];
        if (end - start >= MIN_REPEATED_CHARACTERS && isRepeat(text, start, end, emittedBlocks)) {
            return 1;
        }
        return 0;
    }

    private boolean isRepeat(String text, int start, int end, Map<Long, Integer> emittedBlocks) {
        Integer previous = emittedBlocks.get(fingerprint(text, start, end));
        return previous != null && text.regionMatches(previous, text, start, end - start);
    }

    private void remember(String text, int start, int end, Map<Long, Integer> emittedBlocks) {
        emittedBlocks.putIfAbsent(fingerprint(text, start, end), start);
    }

    private long fingerprint(String text, int start, int end) {
        long fingerprint = HASH_OFFSET ^ (end - start);
        for (int index = start; index < end; index++) {
            fingerprint = (fingerprint ^ text.charAt(index)) * HASH_PRIME;
        }
        return fingerprint;
    }

    private int[] lineEnds(String text) {
        int[] ends = new int[Math.min(1024, text.length())];
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '\n') continue;
            if (count == ends.length) ends = Arrays.copyOf(ends, ends.length * 2);
            ends[count++] = index + 1;
        }
        if (count == 0 || ends[count - 1] < text.length()) {
            if (count == ends.length) ends = Arrays.copyOf(ends, ends.length * 2);
            ends[count++] = text.length();
        }
        return Arrays.copyOf(ends, count);
    }

    private record Page(int start, int end) { }
    private record Line(int start, int end, boolean structured) {
        private int length() { return end - start; }
    }

    public static final class PreparationException extends PermanentProcessingException {
        public PreparationException() {
            super(ProcessingErrorCode.RESOURCE_LIMIT_LLM_INPUT_CHARACTERS);
        }
    }
}
