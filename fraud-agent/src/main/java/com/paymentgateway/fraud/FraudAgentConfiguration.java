package com.paymentgateway.fraud;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FraudAgentConfiguration {
    @Bean
    ChatModel fraudChatModel(
            @Value("${fraud.ai.base-url:http://localhost:11434}") String baseUrl,
            @Value("${fraud.ai.model:llama3.1}") String modelName) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.0)
                .build();
    }

    @Bean
    VelocityAnalystAgent velocityAnalystAgent(ChatModel model) {
        return AiServices.builder(VelocityAnalystAgent.class)
                .chatModel(Objects.requireNonNull(model))
                .build();
    }

    @Bean
    BinAttackDetectorAgent binAttackDetectorAgent(ChatModel model) {
        return AiServices.builder(BinAttackDetectorAgent.class)
                .chatModel(Objects.requireNonNull(model))
                .build();
    }

    @Bean
    FraudDecisionAgent fraudDecisionAgent(ChatModel model) {
        return AiServices.builder(FraudDecisionAgent.class)
                .chatModel(Objects.requireNonNull(model))
                .build();
    }
}
