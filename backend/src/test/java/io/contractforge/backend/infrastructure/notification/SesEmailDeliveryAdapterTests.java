package io.contractforge.backend.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.contractforge.backend.application.notification.EmailDeliveryPort;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

class SesEmailDeliveryAdapterTests {
    @Test
    void mapsOnlyApplicationEmailFieldsToSesTransportRequest() {
        SendEmailRequest[] captured = new SendEmailRequest[1];
        SesClient ses = (SesClient) Proxy.newProxyInstance(SesClient.class.getClassLoader(),
                new Class<?>[] {SesClient.class}, (proxy, method, args) -> {
                    if ("sendEmail".equals(method.getName())) {
                        captured[0] = (SendEmailRequest) args[0];
                        return SendEmailResponse.builder().messageId("provider-id").build();
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType().isPrimitive()) return 0;
                    return null;
                });

        SesEmailDeliveryAdapter adapter = new SesEmailDeliveryAdapter(ses,
                new NotificationDeliverySenderProperties("sender@example.test", "eu-west-1"));
        adapter.send(new EmailDeliveryPort.EmailMessage("owner@example.test", "subject", "body"));

        assertThat(captured[0].source()).isEqualTo("sender@example.test");
        assertThat(captured[0].destination().toAddresses()).containsExactly("owner@example.test");
        assertThat(captured[0].message().subject().data()).isEqualTo("subject");
        assertThat(captured[0].message().body().text().data()).isEqualTo("body");
    }
}
