package com.paymentgateway.webhook;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class WebhookHttpClientConfiguration {
    @Bean(destroyMethod = "dispose")
    ConnectionProvider webhookConnectionProvider(
            @Value("${webhook.http.max-connections:512}") int maxConnections,
            @Value("${webhook.http.pending-acquires:10000}") int pendingAcquires) {
        if (maxConnections < 1 || pendingAcquires < 1) {
            throw new IllegalArgumentException("Webhook HTTP pool limits must be positive");
        }
        return ConnectionProvider.builder("webhook-delivery")
                .maxConnections(maxConnections)
                .pendingAcquireMaxCount(pendingAcquires)
                .pendingAcquireTimeout(Duration.ofMillis(500))
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(5))
                .evictInBackground(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    WebClient.Builder webClientBuilder(
            ConnectionProvider webhookConnectionProvider,
            @Value("${webhook.http.response-timeout-ms:5000}") long responseTimeoutMillis) {
        if (responseTimeoutMillis < 1) {
            throw new IllegalArgumentException("Webhook HTTP response timeout must be positive");
        }
        HttpClient httpClient = HttpClient.create(webhookConnectionProvider)
                .responseTimeout(Duration.ofMillis(responseTimeoutMillis));
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
