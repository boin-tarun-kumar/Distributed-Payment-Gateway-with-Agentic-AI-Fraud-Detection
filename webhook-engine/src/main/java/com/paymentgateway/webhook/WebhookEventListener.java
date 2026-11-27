package com.paymentgateway.webhook;

import com.paymentgateway.events.FraudAlertEvent;
import com.paymentgateway.events.PaymentEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class WebhookEventListener {
    private final WebhookDeliveryService deliveryService;

    public WebhookEventListener(WebhookDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @KafkaListener(topics = "payment.events", groupId = "webhook-engine")
    public void onPaymentEvent(PaymentEvent event) {
        deliveryService.enqueue(event);
    }

    @KafkaListener(topics = "fraud.alerts", groupId = "webhook-engine")
    public void onFraudAlert(FraudAlertEvent event) {
        deliveryService.enqueue(event);
    }
}
