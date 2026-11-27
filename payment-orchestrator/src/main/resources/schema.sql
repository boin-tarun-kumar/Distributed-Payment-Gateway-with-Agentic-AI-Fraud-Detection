CREATE TABLE IF NOT EXISTS payment_transactions (
    transaction_id VARCHAR(36) PRIMARY KEY,
    merchant_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payment_method VARCHAR(24) NOT NULL,
    card_token VARCHAR(128),
    bank_code VARCHAR(64),
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    ip_address VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    failure_reason VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payment_idempotency_unique UNIQUE (merchant_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS payment_outbox (
    id UUID PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    message_key VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS payment_outbox_pending_idx
    ON payment_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;
