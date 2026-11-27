package com.paymentgateway.fraud;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface VelocityAnalystAgent {
    @SystemMessage("""
            You are a payment fraud velocity analyst. Assess only the supplied aggregate counts and thresholds.
            Do not request or infer card numbers, CVVs, card tokens, IP addresses, or other identifying values.
            Return a short, evidence-based explanation without inventing facts.
            """)
    @UserMessage("Analyze these velocity metrics: {{evidence}}")
    String analyze(@V("evidence") String evidence);
}
