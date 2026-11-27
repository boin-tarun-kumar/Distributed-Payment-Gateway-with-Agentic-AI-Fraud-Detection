package com.paymentgateway.fraud;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paymentgateway.events.FraudInvestigateEvent;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FraudInvestigationServiceTest {
    private final FraudInvestigationTools tools = mock(FraudInvestigationTools.class);
    private final VelocityAnalystAgent velocityAnalyst = mock(VelocityAnalystAgent.class);
    private final BinAttackDetectorAgent binAttackDetector = mock(BinAttackDetectorAgent.class);
    private final FraudDecisionAgent decisionAgent = mock(FraudDecisionAgent.class);

    @Test
    void highRiskTokenizedEventHoldsSettlementAndEmitsAlert() {
        when(tools.queryRedisVelocityWindow("merchant-1", "127.0.0.1", 60))
                .thenReturn(new VelocityWindowEvidence(12, 60));
        when(tools.inspectTokenizedTransactionHistory("ct_0123456789abcdef0123456789abcdef"))
                .thenReturn(new TokenHistoryEvidence(3, 0, 1));
        when(tools.detectCoordinatedBinAttack(null)).thenReturn(new BinAttackEvidence(false, 0, 0));
        when(velocityAnalyst.analyze(anyString())).thenReturn("Velocity is elevated.");
        when(binAttackDetector.analyze(anyString())).thenReturn("No BIN cluster data.");
        when(decisionAgent.explain(anyString())).thenReturn("High risk due to repeated payment attempts.");

        FraudInvestigationService service =
                new FraudInvestigationService(tools, velocityAnalyst, binAttackDetector, decisionAgent, 10, 5);
        FraudInvestigateEvent event = new FraudInvestigateEvent(
                "transaction-1",
                "merchant-1",
                "ct_0123456789abcdef0123456789abcdef",
                null,
                "127.0.0.1",
                new BigDecimal("125.00"),
                0.9,
                "ELEVATED_PAYMENT_VELOCITY");

        service.investigate(event);

        verify(tools).placeSettlementPayoutHold(eq("merchant-1"), anyString(), anyString());
        verify(tools).emitFraudRiskAlert(
                anyString(),
                eq("transaction-1"),
                eq("merchant-1"),
                eq("ct_0123456789abcdef0123456789abcdef"),
                eq("HOLD"),
                anyString(),
                eq(true));
        verify(tools).recordInvestigation(
                eq("transaction-1"),
                eq("merchant-1"),
                eq("ct_0123456789abcdef0123456789abcdef"),
                eq(null),
                eq("127.0.0.1"),
                eq(0.9));
    }

    @Test
    void rejectsEventsWithoutAnIrreversibleCardToken() {
        FraudInvestigationService service =
                new FraudInvestigationService(tools, velocityAnalyst, binAttackDetector, decisionAgent, 10, 5);
        FraudInvestigateEvent event = new FraudInvestigateEvent(
                "transaction-1", "merchant-1", null, null, null, new BigDecimal("10.00"), 0.2, "TEST");

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.investigate(event));
        verify(tools, never()).recordInvestigation(any(), any(), any(), any(), any(), anyDouble());
    }
}
