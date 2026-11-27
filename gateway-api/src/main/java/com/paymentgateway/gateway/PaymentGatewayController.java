package com.paymentgateway.gateway;

import com.paymentgateway.events.PaymentStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentGatewayController {
    private final PaymentGatewayService paymentGatewayService;

    public PaymentGatewayController(PaymentGatewayService paymentGatewayService) {
        this.paymentGatewayService = paymentGatewayService;
    }

    @PostMapping("/card")
    public Mono<ResponseEntity<PaymentStatusResponse>> cardPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreatePaymentRequest request,
            ServerWebExchange exchange) {
        return paymentGatewayService
                .submit(request, idempotencyKey, "CARD", sourceIp(exchange))
                .map(PaymentGatewayController::response);
    }

    @PostMapping("/netbanking")
    public Mono<ResponseEntity<PaymentStatusResponse>> netBankingPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreatePaymentRequest request,
            ServerWebExchange exchange) {
        return paymentGatewayService
                .submit(request, idempotencyKey, "NET_BANKING", sourceIp(exchange))
                .map(PaymentGatewayController::response);
    }

    private static ResponseEntity<PaymentStatusResponse> response(PaymentStatusResponse result) {
        if ("BLOCKED".equals(result.status())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(result);
        }
        return ResponseEntity.accepted().body(result);
    }

    private static String sourceIp(ServerWebExchange exchange) {
        if (exchange.getRequest().getRemoteAddress() == null
                || exchange.getRequest().getRemoteAddress().getAddress() == null) {
            return "unknown";
        }
        return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }
}
