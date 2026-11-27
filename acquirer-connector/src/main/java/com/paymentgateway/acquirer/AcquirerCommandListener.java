package com.paymentgateway.acquirer;

import com.paymentgateway.events.SagaCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class AcquirerCommandListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(AcquirerCommandListener.class);

    private final AcquirerAuthorizationService authorizationService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public AcquirerCommandListener(
            AcquirerAuthorizationService authorizationService,
            KafkaTemplate<String, Object> kafkaTemplate) {
        this.authorizationService = authorizationService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = "saga.commands", groupId = "acquirer-connector")
    public void onCommand(SagaCommand command, Acknowledgment acknowledgment) {
        authorizationService
                .authorize(command)
                .flatMap(reply -> Mono.fromFuture(
                                kafkaTemplate.send("saga.replies", command.transactionId(), reply))
                        .then())
                .doOnSuccess(ignored -> acknowledgment.acknowledge())
                .subscribe(
                        ignored -> { },
                        exception -> LOGGER.error(
                                "Could not process acquirer command {}", command.commandId(), exception));
    }
}
