package com.paymentgateway.webhook;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class WebhookHttpClient {
    private final WebhookSigner signer;
    private final WebClient webClient;
    private final Duration responseTimeout;

    public WebhookHttpClient(
            WebhookSigner signer,
            WebClient.Builder webClientBuilder,
            @Value("${webhook.delivery.timeout-ms:5000}") long timeoutMillis) {
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("Webhook timeout must be positive");
        }
        this.signer = signer;
        this.webClient = webClientBuilder.build();
        this.responseTimeout = Duration.ofMillis(timeoutMillis);
    }

    public Mono<Void> deliver(WebhookDelivery delivery) {
        return Mono.defer(() -> {
            String signature = signer.sign(delivery.signingSecret(), delivery.payload());
            return webClient
                    .post()
                    .uri(delivery.endpointUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", signature)
                    .bodyValue(delivery.payload())
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(responseTimeout)
                    .then();
        });
    }
}
