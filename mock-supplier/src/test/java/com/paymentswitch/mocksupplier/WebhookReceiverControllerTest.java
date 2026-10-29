package com.paymentswitch.mocksupplier;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
class WebhookReceiverControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void storesReceivedWebhooksNewestFirst() {
        post("{\"n\":1}", "evt-1");
        post("{\"n\":2}", "evt-2");

        assertThat(mvc.get().uri("/webhooks/shop-a").exchange())
                .bodyJson().extractingPath("$[*].webhookId").asArray().containsExactly("evt-2", "evt-1");
        assertThat(mvc.get().uri("/webhooks/shop-b").exchange()).bodyJson().isEqualTo("[]");
    }

    private void post(String body, String webhookId) {
        assertThat(mvc.post().uri("/webhooks/shop-a").contentType(MediaType.APPLICATION_JSON)
                .header("X-Webhook-Id", webhookId).content(body).exchange()).hasStatusOk();
    }
}
