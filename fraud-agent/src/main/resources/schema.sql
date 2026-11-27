CREATE TABLE IF NOT EXISTS fraud_investigations (
    transaction_id VARCHAR(36) PRIMARY KEY,
    merchant_id VARCHAR(128) NOT NULL,
    card_token VARCHAR(128) NOT NULL,
    bin_prefix CHAR(6),
    source_ip VARCHAR(64),
    risk_score DOUBLE PRECISION NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS fraud_bin_window_idx
    ON fraud_investigations (bin_prefix, detected_at)
    WHERE bin_prefix IS NOT NULL;
