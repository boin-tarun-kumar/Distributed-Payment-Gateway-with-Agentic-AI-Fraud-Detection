CREATE TABLE IF NOT EXISTS webhook_subscriptions (
    subscription_id UUID PRIMARY KEY,
    merchant_id VARCHAR(128) NOT NULL,
    endpoint_url TEXT NOT NULL,
    signing_secret TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT webhook_subscription_endpoint_unique UNIQUE (merchant_id, endpoint_url)
);

CREATE TABLE IF NOT EXISTS webhook_deliveries (
    delivery_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL REFERENCES webhook_subscriptions (subscription_id),
    merchant_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_failure_code VARCHAR(128),
    CONSTRAINT webhook_delivery_event_unique UNIQUE (subscription_id, event_id)
);

CREATE INDEX IF NOT EXISTS webhook_deliveries_due_idx
    ON webhook_deliveries (status, next_attempt_at, created_at);

CREATE TABLE IF NOT EXISTS webhook_dead_letters (
    delivery_id UUID PRIMARY KEY REFERENCES webhook_deliveries (delivery_id),
    merchant_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    endpoint_url TEXT NOT NULL,
    payload TEXT NOT NULL,
    attempts INTEGER NOT NULL,
    failure_code VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS webhook_dlq_pending_idx
    ON webhook_dead_letters (created_at)
    WHERE published_at IS NULL;
