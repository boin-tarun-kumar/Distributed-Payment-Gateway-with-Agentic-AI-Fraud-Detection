# Project Architecture: Distributed Payment Gateway & Agentic AI Fraud Detection

## Overview
A PCI-compliant distributed payment gateway (like Razorpay/Stripe) built with Java 21, Spring Boot 3.x, Spring WebFlux, Kafka, Redis, PostgreSQL, Resilience4J, and LangChain4j, engineered to sustain 10,000 transactions/sec.

## 7 Microservices Architecture (+ `common-events` library)
1. `gateway-api`: Reactive Spring WebFlux edge service handling Card and Net Banking payment requests, idempotency keys, and Tier-1 sub-millisecond Redis velocity gating.
2. `card-vault`: Tokenizes raw card PANs in-memory (<50ms) using AES-256. **STRICT PCI RULE:** Raw PANs/CVVs must NEVER be logged, saved to DB, or published to Kafka. Byte arrays must be zeroized immediately after tokenization. Only irreversible `cardToken` values leave this service.
3. `payment-orchestrator`: Manages the distributed SAGA state machine, Transactional Outbox event publishing, and Resilience4J circuit breakers across payment flows to eliminate double-charges.
4. `acquirer-connector`: Handles external Card network and Net Banking bank integrations with Resilience4J circuit breakers, bulkheads, and simulated bank latency/failures.
5. `fraud-agent`: Asynchronous LangChain4j multi-agent service consuming `fraud.investigate` Kafka events. Uses `@Tool` methods to inspect Redis velocity metrics and Postgres history, detect BIN attacks, place payout holds in `settlement-service`, and emit `fraud.alerts`.
6. `webhook-engine`: Delivers HTTP POST webhooks to merchants with HMAC-SHA256 signing (`X-Signature`), 7-attempt exponential backoff, and a Dead-Letter Queue (DLQ).
7. `settlement-service`: Nightly batch settlement engine computing per-merchant net payouts while enforcing automated fraud holds placed by `fraud-agent`.

## Kafka Topics & Shared Contracts (`common-events`)
- `payment.events`: `{ transactionId, merchantId, paymentMethod, cardToken, amount, currency, status, ipAddress, timestamp }`
- `saga.commands` / `saga.replies`: SAGA orchestration events across `payment-orchestrator`, `acquirer-connector`, and `settlement-service`.
- `fraud.investigate`: `{ transactionId, merchantId, cardToken, binPrefix, amount, riskScore, triggerReason }`
- `fraud.alerts`: `{ alertId, transactionId, merchantId, cardToken, decision, aiSummary, holdSettlement }`

## High-Throughput & Coding Standards
- Use Java 21 records for DTOs and Kafka payloads.
- Keep `gateway-api` and `acquirer-connector` 100% non-blocking (Spring WebFlux + Reactive Redis).
- Configure multi-partition Kafka topics, Netty event-loop tuning, and connection pooling to support 10,000 TPS.
- Include JUnit 5 and Mockito tests for PCI zeroization, SAGA idempotency, LangChain4j `@Tool` calls, and HMAC webhook retries.
