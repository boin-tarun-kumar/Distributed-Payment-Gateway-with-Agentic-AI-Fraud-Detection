package com.paymentgateway.fraud;

import com.paymentgateway.events.FraudInvestigateEvent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FraudInvestigationService {
    private final FraudInvestigationTools tools;
    private final VelocityAnalystAgent velocityAnalyst;
    private final BinAttackDetectorAgent binAttackDetector;
    private final FraudDecisionAgent decisionAgent;
    private final long velocityReviewThreshold;
    private final long declineHoldThreshold;

    public FraudInvestigationService(
            FraudInvestigationTools tools,
            VelocityAnalystAgent velocityAnalyst,
            BinAttackDetectorAgent binAttackDetector,
            FraudDecisionAgent decisionAgent,
            @Value("${fraud.velocity.review-threshold:10}") long velocityReviewThreshold,
            @Value("${fraud.history.decline-hold-threshold:5}") long declineHoldThreshold) {
        this.tools = tools;
        this.velocityAnalyst = velocityAnalyst;
        this.binAttackDetector = binAttackDetector;
        this.decisionAgent = decisionAgent;
        this.velocityReviewThreshold = velocityReviewThreshold;
        this.declineHoldThreshold = declineHoldThreshold;
    }

    public void investigate(FraudInvestigateEvent event) {
        validateTokenizedCardEvent(event);
        tools.recordInvestigation(
                event.transactionId(),
                event.merchantId(),
                event.cardToken(),
                event.binPrefix(),
                event.ipAddress(),
                event.riskScore());

        VelocityWindowEvidence velocity = tools.queryRedisVelocityWindow(
                event.merchantId(), event.ipAddress(), 60);
        TokenHistoryEvidence history = tools.inspectTokenizedTransactionHistory(event.cardToken());
        BinAttackEvidence binAttack = tools.detectCoordinatedBinAttack(event.binPrefix());

        String velocityAnalysis = velocityAnalyst.analyze(
                "Attempts in the last " + velocity.windowSeconds() + " seconds: " + velocity.attempts()
                        + "; review threshold: " + velocityReviewThreshold
                        + "; gateway risk score: " + event.riskScore());
        String binAnalysis = binAttackDetector.analyze(
                "Distinct tokenized cards in recent BIN investigations: " + binAttack.distinctCards()
                        + "; affected merchants: " + binAttack.merchants()
                        + "; BIN evidence available: " + (event.binPrefix() != null));

        boolean holdSettlement = event.riskScore() >= 0.85
                || velocity.attempts() >= velocityReviewThreshold * 2
                || history.declines() >= declineHoldThreshold
                || binAttack.detected();
        String decision = holdSettlement
                ? "HOLD"
                : event.riskScore() >= 0.5 || velocity.attempts() >= velocityReviewThreshold
                        ? "REVIEW"
                        : "ALLOW";

        String decisionFacts = "Decision: " + decision
                + "; gateway risk score: " + event.riskScore()
                + "; velocity attempts: " + velocity.attempts()
                + "; token history transactions: " + history.transactions()
                + "; token history declines: " + history.declines()
                + "; token history merchants: " + history.merchants()
                + "; coordinated BIN attack: " + binAttack.detected()
                + "; velocity analyst: " + velocityAnalysis
                + "; BIN analyst: " + binAnalysis;
        String summary = sanitizeSummary(decisionAgent.explain(decisionFacts));
        String alertId = UUID.nameUUIDFromBytes(
                        ("fraud-alert:" + event.transactionId()).getBytes(StandardCharsets.UTF_8))
                .toString();

        if (holdSettlement) {
            tools.placeSettlementPayoutHold(event.merchantId(), alertId, summary);
        }
        tools.emitFraudRiskAlert(
                alertId,
                event.transactionId(),
                event.merchantId(),
                event.cardToken(),
                decision,
                summary,
                holdSettlement);
    }

    private static void validateTokenizedCardEvent(FraudInvestigateEvent event) {
        if (event == null
                || event.transactionId() == null
                || event.transactionId().isBlank()
                || event.merchantId() == null
                || event.merchantId().isBlank()
                || event.cardToken() == null
                || !event.cardToken().matches("ct_[0-9a-f]{32}")
                || !Double.isFinite(event.riskScore())
                || event.riskScore() < 0
                || event.riskScore() > 1) {
            throw new IllegalArgumentException("Fraud investigations require a tokenized card event");
        }
    }

    private static String sanitizeSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            throw new IllegalStateException("Fraud decision agent returned no explanation");
        }
        String sanitized = summary
                .replaceAll("ct_[0-9a-fA-F]{32}", "[token]")
                .replaceAll("(?<!\\d)\\d{12,19}(?!\\d)", "[redacted]");
        return sanitized.length() <= 1000 ? sanitized : sanitized.substring(0, 1000);
    }
}
