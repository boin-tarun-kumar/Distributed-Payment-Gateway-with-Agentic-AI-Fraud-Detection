package com.paymentgateway.webhook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WebhookEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebhookEngineApplication.class, args);
    }
}
