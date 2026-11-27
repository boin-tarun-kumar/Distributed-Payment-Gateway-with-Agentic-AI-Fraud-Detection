package com.paymentgateway.gateway;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class GatewayHttpClientConfiguration {
    @Bean(destroyMethod = "dispose")
    ConnectionProvider gatewayConnectionProvider(
            @Value("${gateway.http.max-connections:1024}") int maxConnections,
            @Value("${gateway.http.pending-acquires:10000}") int pendingAcquires) {
        if (maxConnections < 1 || pendingAcquires < 1) {
            throw new IllegalArgumentException("Gateway HTTP pool limits must be positive");
        }
        return ConnectionProvider.builder("gateway-orchestrator")
                .maxConnections(maxConnections)
                .pendingAcquireMaxCount(pendingAcquires)
                .pendingAcquireTimeout(Duration.ofMillis(200))
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(5))
                .evictInBackground(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    WebClient.Builder webClientBuilder(
            ConnectionProvider gatewayConnectionProvider,
            @Value("${gateway.http.response-timeout-ms:5000}") long responseTimeoutMillis) {
        if (responseTimeoutMillis < 1) {
            throw new IllegalArgumentException("Gateway HTTP response timeout must be positive");
        }
        HttpClient httpClient = HttpClient.create(gatewayConnectionProvider)
                .responseTimeout(Duration.ofMillis(responseTimeoutMillis));
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
