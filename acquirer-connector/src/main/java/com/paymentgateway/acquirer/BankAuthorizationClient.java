package com.paymentgateway.acquirer;

import com.paymentgateway.events.SagaCommand;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class BankAuthorizationClient {
    private final Duration simulatedLatency;
    private final boolean simulatedFailure;

    public BankAuthorizationClient(
            @Value("${acquirer.simulation.latency-ms:20}") long latencyMillis,
            @Value("${acquirer.simulation.failure:false}") boolean simulatedFailure) {
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("Acquirer latency must not be negative");
        }
        this.simulatedLatency = Duration.ofMillis(latencyMillis);
        this.simulatedFailure = simulatedFailure;
    }

    public Mono<String> authorize(SagaCommand command) {
        return Mono.delay(simulatedLatency)
                .flatMap(ignored -> {
                    if (simulatedFailure || "SIMULATE_FAILURE".equals(command.bankCode())) {
                        return Mono.error(new BankUnavailableException("Acquirer authorization unavailable"));
                    }
                    return Mono.just("acq_" + command.transactionId());
                });
    }
}
