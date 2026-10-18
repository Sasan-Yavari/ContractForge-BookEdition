package io.contractforge.backend.infrastructure.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.extraction.OcrTextExtractorPort;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.TextractException;

/** Amazon Textract adapter; AWS SDK types do not cross the application boundary. */
@Component
@Profile("!test & !local")
final class TextractOcrAdapter implements OcrTextExtractorPort {
    private final TextractClient textract;
    private final ObjectMapper json;

    TextractOcrAdapter(TextractClient textract, ObjectMapper json) { this.textract = textract; this.json = json; }

    @Override
    public OcrResult extract(byte[] document, OcrAttemptRecorder attemptRecorder) {
        try {
            var request = DetectDocumentTextRequest.builder()
                    .document(d -> d.bytes(SdkBytes.fromByteArray(document))).build();
            attemptRecorder.recordInvocation();
            var response = textract.detectDocumentText(request);
            String text = response.blocks().stream().filter(block -> block.blockType() == BlockType.LINE)
                    .map(block -> block.text()).filter(java.util.Objects::nonNull).collect(Collectors.joining("\n"));
            Integer pages = response.documentMetadata() == null ? null : response.documentMetadata().pages();
            try {
                return new OcrResult(text, new JsonValue(json.writeValueAsString(new PageUsage(pages))));
            } catch (JsonProcessingException failure) {
                throw new PermanentOcrException(failure);
            }
        } catch (TextractException failure) {
            if (failure.statusCode() == 429 || failure.statusCode() >= 500) {
                throw new RetryableOcrException(failure);
            }
            throw new PermanentOcrException(failure);
        }
    }

    private record PageUsage(Integer pages) { }
}
