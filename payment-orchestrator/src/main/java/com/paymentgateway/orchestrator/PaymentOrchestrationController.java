package com.paymentgateway.orchestrator;

import com.paymentgateway.events.PaymentStatusResponse;
import com.paymentgateway.events.PaymentSubmission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orchestrations")
public class PaymentOrchestrationController {
    private final PaymentOrchestrationService orchestrationService;

    public PaymentOrchestrationController(PaymentOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @PostMapping
    public ResponseEntity<PaymentStatusResponse> initiate(@RequestBody PaymentSubmission submission) {
        PaymentStatusResponse response = orchestrationService.initiate(submission);
        HttpStatus status = "PROCESSING".equals(response.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
