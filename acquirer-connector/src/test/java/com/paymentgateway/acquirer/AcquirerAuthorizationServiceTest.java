package com.paymentgateway.acquirer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paymentgateway.events.SagaCommand;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

class AcquirerAuthorizationServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void concurrentDuplicateCommandDoesNotCallAcquirerAgain() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        BankAuthorizationClient bankClient = mock(BankAuthorizationClient.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(Mono.just(false));
        when(values.get(anyString())).thenReturn(Mono.just("PROCESSING"));

        AcquirerAuthorizationService service = new AcquirerAuthorizationService(
                redis,
                bankClient,
                new AcquirerResilienceConfiguration().acquirerCircuitBreaker(),
                new AcquirerResilienceConfiguration().acquirerRetry());
        SagaCommand command = new SagaCommand(
                "cmd-1",
                "tx-1",
                "merchant-1",
                "key-1",
                "CARD",
                "ct_0123456789abcdef0123456789abcdef",
                null,
                new BigDecimal("10.00"),
                "USD",
                "AUTHORIZE",
                Instant.now());

        org.junit.jupiter.api.Assertions.assertNull(service.authorize(command).block());

        verify(bankClient, never()).authorize(any());
    }
}
