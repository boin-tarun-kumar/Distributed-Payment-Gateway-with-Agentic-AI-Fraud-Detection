package com.paymentgateway.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paymentgateway.events.PaymentStatusResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class PaymentGatewayServiceTest {
    @SuppressWarnings("unchecked")
    private final ReactiveValueOperations<String, String> values =
            mock(ReactiveValueOperations.class);

    @Test
    void duplicateIdempotencyKeyReturnsCachedResponseWithoutRepeatingPayment() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        RedisVelocityGate velocityGate = mock(RedisVelocityGate.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
                .thenReturn(Mono.just(false));
        when(values.get(anyString())).thenReturn(Mono.just("RESPONSE|dHgtMQ|U1VDQ0VFREVE|~"));

        PaymentGatewayService service = new PaymentGatewayService(
                redis,
                velocityGate,
                mock(KafkaTemplate.class),
                WebClient.builder(),
                "http://localhost:8082",
                30);

        PaymentStatusResponse response = service.submit(
                        new CreatePaymentRequest(
                                "merchant-1",
                                new BigDecimal("10.00"),
                                "USD",
                                "ct_0123456789abcdef0123456789abcdef",
                                null),
                        "key-1",
                        "CARD",
                        "127.0.0.1")
                .block();

        assertEquals("tx-1", response.transactionId());
        assertEquals("SUCCEEDED", response.status());
        verify(velocityGate, never()).inspect(anyString(), anyString(), anyString());
    }
}
