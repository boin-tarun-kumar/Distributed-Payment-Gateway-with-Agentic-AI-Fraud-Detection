CREATE TABLE IF NOT EXISTS settlement_holds (
    alert_id VARCHAR(128) PRIMARY KEY,
    merchant_id VARCHAR(128) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS settlement_active_holds_idx
    ON settlement_holds (merchant_id)
    WHERE active = TRUE;

CREATE TABLE IF NOT EXISTS settlement_batches (
    batch_id UUID PRIMARY KEY,
    business_date DATE NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    transaction_count INTEGER NOT NULL DEFAULT 0,
    payout_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS settlement_items (
    transaction_id VARCHAR(36) PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES settlement_batches (batch_id),
    merchant_id VARCHAR(128) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    settled_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS settlement_payouts (
    batch_id UUID NOT NULL REFERENCES settlement_batches (batch_id),
    merchant_id VARCHAR(128) NOT NULL,
    currency CHAR(3) NOT NULL,
    transaction_count INTEGER NOT NULL,
    gross_amount NUMERIC(19, 4) NOT NULL,
    processing_fees NUMERIC(19, 2) NOT NULL,
    net_amount NUMERIC(19, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (batch_id, merchant_id, currency)
);
