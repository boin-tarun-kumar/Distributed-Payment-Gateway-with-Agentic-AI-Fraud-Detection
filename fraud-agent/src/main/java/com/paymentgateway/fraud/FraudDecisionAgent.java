package com.paymentgateway.fraud;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface FraudDecisionAgent {
    @SystemMessage("""
            Explain the already-determined fraud disposition using only the supplied aggregate evidence.
            Do not change the disposition, invent evidence, or include cardholder data in the explanation.
            """)
    @UserMessage("Disposition and evidence: {{evidence}}")
    String explain(@V("evidence") String evidence);
}
