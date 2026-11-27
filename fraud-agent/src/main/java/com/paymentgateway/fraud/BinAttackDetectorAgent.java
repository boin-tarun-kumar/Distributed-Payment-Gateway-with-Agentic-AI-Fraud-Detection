package com.paymentgateway.fraud;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface BinAttackDetectorAgent {
    @SystemMessage("""
            You identify coordinated payment-card BIN attacks from aggregate database counts only.
            Treat missing BIN data as unavailable evidence, never as proof of safety.
            Do not request or infer PANs, CVVs, card tokens, or merchant identities.
            """)
    @UserMessage("Assess these aggregate BIN and card-history metrics: {{evidence}}")
    String analyze(@V("evidence") String evidence);
}
