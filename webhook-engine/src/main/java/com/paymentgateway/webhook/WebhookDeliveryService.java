package com.paymentgateway.webhook;

import com.paymentgateway.events.FraudAlertEvent;
import com.paymentgateway.events.PaymentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookDeliveryService {
    private final WebhookDeliveryRepository repository;
    private final WebhookPayloadEncoder payloadEncoder;

    public WebhookDeliveryService(
            WebhookDeliveryRepository repository,
            WebhookPayloadEncoder payloadEncoder) {
        this.repository = repository;
        this.payloadEncoder = payloadEncoder;
    }

    @Transactional
    public void enqueue(PaymentEvent event) {
        repository.enqueue(event, payloadEncoder.encode(event));
    }

    @Transactional
    public void enqueue(FraudAlertEvent event) {
        repository.enqueue(event, payloadEncoder.encode(event));
    }
}
