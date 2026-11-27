# Distributed Payment Gateway with Agentic AI Fraud Detection

A Java 21, Spring Boot 4.1.3 multi-module project demonstrating a distributed payment gateway built around Kafka, Redis, and PostgreSQL. The system uses tokenized card data, an idempotent payment SAGA, an asynchronous fraud workflow, durable webhook delivery, and scheduled settlements.

> **Development project, not a production payment processor or PCI DSS certification.** The Compose file uses local development credentials, and the acquirer is simulated. Do not use real cardholder data or expose these services publicly.

## Architecture

The Maven reactor contains the shared `common-events` library and seven independently runnable services:

| Module | Port | Responsibility |
| --- | ---: | --- |
| `gateway-api` | 8080 | Reactive card and net-banking APIs, Redis idempotency, velocity checks, and payment submission |
| `card-vault` | 8081 | In-memory card tokenization using AES-256-CMAC; sensitive byte buffers are cleared after tokenization |
| `payment-orchestrator` | 8082 | Payment SAGA state, PostgreSQL-backed idempotency, transactional outbox, and recovery of stalled transactions |
| `acquirer-connector` | 8083 | Simulated bank authorization with Redis deduplication and Resilience4j retry/circuit-breaker policies |
| `fraud-agent` | 8084 | Token-only fraud investigations using LangChain4j agents, Redis/PostgreSQL evidence, payout holds, and explainable alerts |
| `webhook-engine` | 8085 | Durable payment/fraud webhook deliveries, HMAC-SHA256 signatures, retries, and Kafka DLQ publishing |
| `settlement-service` | 8086 | Scheduled merchant settlement calculations and fraud-related payout holds |

`common-events` contains the shared Java records. Kafka topics include `payment.events`, `saga.commands`, `saga.replies`, `fraud.investigate`, `fraud.alerts`, and `webhook.dlq`. The local Kafka setup creates these topics with 24 partitions.

### Payment flow

1. The browser SDK sends card details directly to `card-vault`; the gateway receives only the resulting `cardToken`.
2. `gateway-api` checks the idempotency key and applies an atomic Redis sliding-window velocity gate. Suspicious tokenized-card requests are sent for fraud investigation.
3. `payment-orchestrator` records the payment and SAGA command together with its outbox entry. The acquirer authorizes the command and replies asynchronously.
4. The orchestrator records the final result and publishes a `payment.events` event through its outbox.
5. `fraud-agent` investigates flagged payments, may place a settlement hold, and emits a `fraud.alerts` event.
6. `webhook-engine` queues merchant deliveries, signs the exact JSON body in `X-Signature`, and retries failed requests before forwarding exhausted deliveries to `webhook.dlq`.
7. `settlement-service` calculates net payouts on its configured schedule, excluding payouts on hold.

## Requirements

- Java 21
- Maven 3.9+
- Docker with the Docker Compose plugin (for Kafka, Redis, and PostgreSQL)
- Optional: Ollama with the configured model available for local AI fraud analysis
- Optional: k6 to run the load test

## Run locally

Start the infrastructure from the repository root:

```bash
docker compose up -d
```

Set a local 32-byte vault key (64 hexadecimal characters). Keep it out of source control:

```bash
export CARD_VAULT_AES_KEY="$(openssl rand -hex 32)"
```

Build the reactor and run its tests:

```bash
mvn clean test
```

To package runnable service jars:

```bash
mvn clean package
```

After packaging, run each service in a separate terminal:

| Service | Command |
| --- | --- |
| Card vault | `java -jar card-vault/target/card-vault-1.0.0-SNAPSHOT.jar` |
| Gateway API | `java -jar gateway-api/target/gateway-api-1.0.0-SNAPSHOT.jar` |
| Payment orchestrator | `java -jar payment-orchestrator/target/payment-orchestrator-1.0.0-SNAPSHOT.jar` |
| Acquirer connector | `java -jar acquirer-connector/target/acquirer-connector-1.0.0-SNAPSHOT.jar` |
| Fraud agent | `java -jar fraud-agent/target/fraud-agent-1.0.0-SNAPSHOT.jar` |
| Webhook engine | `java -jar webhook-engine/target/webhook-engine-1.0.0-SNAPSHOT.jar` |
| Settlement service | `java -jar settlement-service/target/settlement-service-1.0.0-SNAPSHOT.jar` |

The Compose stack starts infrastructure only, not the Java services. Services default to local dependency addresses such as `localhost:9092`, `localhost:6379`, and `localhost:5432`. When running a service inside a container, configure dependency addresses using the container-network hostnames (for example `kafka:9092`, `redis:6379`, and `postgres:5432`).

Fraud analysis defaults to Ollama at `http://localhost:11434` with model `llama3.1`. Start Ollama and make that model available, or configure `FRAUD_AI_BASE_URL` and `FRAUD_AI_MODEL` for your local setup.

## API examples

### Tokenize a card

The browser helper is in [`sdk/vault-tokenize.js`](./sdk/vault-tokenize.js). It posts card data directly to the vault and returns only the token. The vault allows HTTP only for `localhost`; use HTTPS for other hosts.

```http
POST http://localhost:8081/api/v1/tokens
Content-Type: application/json

{"cardNumber":"4111111111111111","cvv":"123"}
```

Example response:

```json
{"cardToken":"ct_<token>"}
```

Use test data only. The token is not reversible and is not encryption of the original PAN.

### Submit a card payment

Use a token returned by the vault and a unique idempotency key for each logical payment:

```http
POST http://localhost:8080/api/v1/payments/card
Content-Type: application/json
Idempotency-Key: order-123-payment-1

{
  "merchantId": "merchant-123",
  "amount": 12.50,
  "currency": "USD",
  "cardToken": "ct_<token>"
}
```

The gateway responds with an accepted payment status; the SAGA completes asynchronously.

### Submit a net-banking payment

```http
POST http://localhost:8080/api/v1/payments/netbanking
Content-Type: application/json
Idempotency-Key: order-124-payment-1

{
  "merchantId": "merchant-123",
  "amount": 12.50,
  "currency": "USD",
  "bankCode": "BANK"
}
```

## Configuration

Configuration is supplied through environment variables; the defaults are intended for local development.

| Variable | Purpose | Default |
| --- | --- | --- |
| `CARD_VAULT_AES_KEY` | 32-byte AES key encoded as 64 hex characters; required by `card-vault` | Required |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker address | `localhost:9092` |
| `REDIS_HOST`, `REDIS_PORT` | Redis connection | `localhost`, `6379` |
| `DATABASE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/payments` |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | PostgreSQL credentials | `payments`, `local-dev-only` |
| `FRAUD_AI_BASE_URL`, `FRAUD_AI_MODEL` | Local fraud-model endpoint and model | `http://localhost:11434`, `llama3.1` |
| `SETTLEMENT_CRON`, `SETTLEMENT_TIME_ZONE` | Settlement schedule | `0 0 2 * * *`, `UTC` |
| `SETTLEMENT_PROCESSING_FEE_RATE`, `SETTLEMENT_PROCESSING_FEE_FIXED` | Settlement fee components | `0.029`, `0.30` |

The webhook engine reads active merchant endpoints and signing secrets from the `webhook_subscriptions` PostgreSQL table. Provision subscriptions through a trusted administrative process; this project does not expose a public subscription-management API.

## Containers

Each service has a multi-stage Java 21 Dockerfile. Build from the repository root so Maven can access the full reactor:

```bash
docker build -f <module>/Dockerfile -t payment-gateway/<module>:local .
```

Use `gateway-api`, `card-vault`, `payment-orchestrator`, `acquirer-connector`, `fraud-agent`, `webhook-engine`, or `settlement-service` for `<module>`. Provide the required environment variables and dependency addresses when running the images.

## Tests and load test

Run all module tests with Java 21:

```bash
mvn test
```

The tests cover vault byte-array zeroization, gateway and SAGA idempotency, outbox publishing, acquirer duplicate suppression, fraud holds, settlement fee calculation, and webhook signatures/retry policy.

The k6 script targets the gateway at a constant arrival rate of 10,000 requests per second:

```bash
k6 run -e GATEWAY_URL=http://localhost:8080 load-test/payment-10k-tps.js
```

The script spreads requests across merchant IDs to avoid triggering the per-merchant velocity gate. A 10k TPS arrival-rate target is a benchmark configuration, not a performance guarantee; size the generator and all dependent services appropriately.

## Security notes

- Never log, persist, or publish raw PAN/CVV values. Keep card details scoped to the vault request and use only `cardToken` downstream.
- Supply `CARD_VAULT_AES_KEY` through a secret manager in deployed environments; do not reuse development keys.
- The Compose PostgreSQL credentials and plaintext local Kafka configuration are for development only.
- Terminate TLS at an appropriately secured edge in deployed environments. The local demo does not provide production merchant authentication, key management, or PCI DSS controls.
