package io.contractforge.backend.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.DocumentMetadata;

class TextractOcrAdapterTests {
    @Test
    void returnsTheProviderBilledPageCount() {
        var response = DetectDocumentTextResponse.builder()
                .documentMetadata(DocumentMetadata.builder().pages(3).build()).build();
        TextractClient client = (TextractClient) java.lang.reflect.Proxy.newProxyInstance(
                TextractClient.class.getClassLoader(), new Class<?>[] {TextractClient.class},
                (proxy, method, args) -> method.getName().equals("detectDocumentText") ? response : null);
        TextractOcrAdapter adapter = new TextractOcrAdapter(client, new ObjectMapper());
        AtomicBoolean invoked = new AtomicBoolean();

        var result = adapter.extract(new byte[] {1}, () -> invoked.set(true));

        assertThat(invoked).isTrue();
        assertThat(result.usageData().value()).isEqualTo("{\"pages\":3}");
    }
}
