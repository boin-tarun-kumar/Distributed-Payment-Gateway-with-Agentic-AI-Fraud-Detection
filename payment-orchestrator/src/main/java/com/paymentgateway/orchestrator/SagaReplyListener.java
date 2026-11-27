package com.paymentgateway.orchestrator;

import com.paymentgateway.events.SagaReply;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SagaReplyListener {
    private final PaymentOrchestrationService orchestrationService;

    public SagaReplyListener(PaymentOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @KafkaListener(topics = "saga.replies", groupId = "payment-orchestrator")
    public void onSagaReply(SagaReply reply) {
        orchestrationService.handleReply(reply);
    }
}
