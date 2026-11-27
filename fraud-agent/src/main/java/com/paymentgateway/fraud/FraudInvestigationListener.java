package com.paymentgateway.fraud;

import com.paymentgateway.events.FraudInvestigateEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FraudInvestigationListener {
    private final FraudInvestigationService investigationService;

    public FraudInvestigationListener(FraudInvestigationService investigationService) {
        this.investigationService = investigationService;
    }

    @KafkaListener(topics = "fraud.investigate", groupId = "fraud-agent")
    public void onFraudInvestigation(FraudInvestigateEvent event) {
        investigationService.investigate(event);
    }
}
