package com.paymentgateway.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.paymentgateway.events.PaymentStatusResponse;
import com.paymentgateway.events.PaymentSubmission;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PaymentOrchestrationServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void duplicateMerchantIdempotencyKeyDoesNotCreateAnotherSagaOrOutboxCommand() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OutboxRepository outboxRepository = mock(OutboxRepository.class);
        PaymentStatusResponse existing = new PaymentStatusResponse("tx-original", "PROCESSING", null);
        doReturn(0).when(jdbcTemplate).update(anyString(), any(Object[].class));
        doReturn(existing)
                .when(jdbcTemplate)
                .queryForObject(anyString(), any(RowMapper.class), any(Object[].class));

        PaymentOrchestrationService service =
                new PaymentOrchestrationService(jdbcTemplate, outboxRepository);
        PaymentSubmission duplicate = new PaymentSubmission(
                "tx-retry",
                "merchant-1",
                "merchant-key-1",
                "CARD",
                "ct_0123456789abcdef0123456789abcdef",
                null,
                new BigDecimal("10.00"),
                "USD",
                "127.0.0.1");

        PaymentStatusResponse response = service.initiate(duplicate);

        assertEquals(existing, response);
        verify(outboxRepository, never()).enqueue(anyString(), anyString(), any());
    }
}
