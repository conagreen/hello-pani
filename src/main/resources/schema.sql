CREATE TABLE IF NOT EXISTS product (
    product_id      BIGINT       NOT NULL,
    name            VARCHAR(255) NOT NULL,
    price           BIGINT       NOT NULL,
    image_url       VARCHAR(500),
    check_in_at     DATETIME(6)  NOT NULL,
    check_out_at    DATETIME(6)  NOT NULL,
    sales_open_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (product_id)
);

CREATE TABLE IF NOT EXISTS stock (
    product_id BIGINT      NOT NULL,
    qty        INT         NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (product_id),
    CONSTRAINT chk_stock_qty CHECK (qty >= 0)
);

CREATE TABLE IF NOT EXISTS checkout (
    checkout_id              CHAR(36)    NOT NULL,
    user_id                  VARCHAR(64) NOT NULL,
    product_id               BIGINT      NOT NULL,
    quoted_price             BIGINT      NOT NULL,
    available_point_snapshot BIGINT      NOT NULL,
    status                   VARCHAR(16) NOT NULL,
    expires_at               DATETIME(6) NOT NULL,
    created_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (checkout_id),
    CONSTRAINT chk_checkout_status CHECK (status IN ('ISSUED','USED','EXPIRED'))
);

CREATE TABLE IF NOT EXISTS booking (
    booking_id   BIGINT      NOT NULL AUTO_INCREMENT,
    checkout_id  CHAR(36)    NOT NULL,
    user_id      VARCHAR(64) NOT NULL,
    product_id   BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL,
    total_amount BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    confirmed_at DATETIME(6) NULL,
    PRIMARY KEY (booking_id),
    CONSTRAINT uk_booking_checkout UNIQUE (checkout_id),
    CONSTRAINT chk_booking_status CHECK (status IN ('PENDING_PAYMENT','CONFIRMED','FAILED'))
);

CREATE TABLE IF NOT EXISTS payment (
    payment_id          BIGINT      NOT NULL AUTO_INCREMENT,
    checkout_id         CHAR(36)    NOT NULL,
    booking_id          BIGINT      NOT NULL,
    user_id             VARCHAR(64) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    total_amount        BIGINT      NOT NULL,
    pg_idempotency_key  VARCHAR(64) NOT NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at        DATETIME(6) NULL,
    PRIMARY KEY (payment_id),
    CONSTRAINT uk_payment_checkout UNIQUE (checkout_id),
    CONSTRAINT chk_payment_status CHECK (status IN ('PROCESSING','RESULT_PENDING','SUCCEEDED','FAILED','COMPENSATING','COMPENSATED','REFUND_FAILED'))
);

CREATE TABLE IF NOT EXISTS payment_component (
    payment_component_id    BIGINT       NOT NULL AUTO_INCREMENT,
    payment_id              BIGINT       NOT NULL,
    method                  VARCHAR(16)  NOT NULL,
    amount                  BIGINT       NOT NULL,
    status                  VARCHAR(16)  NOT NULL,
    external_transaction_id VARCHAR(128) NULL,
    PRIMARY KEY (payment_component_id),
    CONSTRAINT chk_payment_component_method CHECK (method IN ('CARD','Y_PAY','POINT'))
);

CREATE TABLE IF NOT EXISTS point_account (
    user_id    VARCHAR(64) NOT NULL,
    balance    BIGINT      NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT chk_point_account_balance CHECK (balance >= 0)
);

CREATE TABLE IF NOT EXISTS point_ledger (
    point_ledger_id BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(64) NOT NULL,
    checkout_id     CHAR(36)    NOT NULL,
    amount          BIGINT      NOT NULL,
    reason          VARCHAR(20) NOT NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (point_ledger_id),
    CONSTRAINT uk_point_ledger_idem UNIQUE (checkout_id, reason),
    CONSTRAINT chk_point_ledger_reason CHECK (reason IN ('BOOKING_USE','BOOKING_REFUND','BOOKING_RESTORE'))
);

INSERT INTO product (product_id, name, price, image_url, check_in_at, check_out_at, sales_open_at)
VALUES (1, '한정 패키지', 150000, 'https://example.com/p1.jpg',
        '2026-06-01 15:00:00', '2026-06-02 11:00:00', '2026-05-15 10:00:00')
ON DUPLICATE KEY UPDATE product_id = product_id;

INSERT INTO stock (product_id, qty)
VALUES (1, 10)
ON DUPLICATE KEY UPDATE product_id = product_id;

INSERT INTO point_account (user_id, balance)
VALUES ('test-user-1', 50000)
ON DUPLICATE KEY UPDATE user_id = user_id;
