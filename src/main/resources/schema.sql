CREATE TABLE IF NOT EXISTS urls (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    code             VARCHAR(32)   NOT NULL,
    long_url         VARCHAR(2048) NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at       TIMESTAMP     NULL,
    click_count      BIGINT        NOT NULL DEFAULT 0,
    last_accessed_at TIMESTAMP     NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_urls_code UNIQUE (code)
);
