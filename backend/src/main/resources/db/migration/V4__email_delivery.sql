CREATE TABLE email_preferences (
 user_id BIGINT PRIMARY KEY REFERENCES users(id),
 verified_at TIMESTAMPTZ,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 enabled_at TIMESTAMPTZ,
 last_request_at TIMESTAMPTZ,
 request_count INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE email_verification_tokens (
 token_hash CHAR(64) PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES users(id),
 expires_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE email_deliveries (
 notification_id BIGINT PRIMARY KEY REFERENCES notifications(id),
 status VARCHAR(20) NOT NULL DEFAULT 'Pending' CHECK(status IN ('Pending','Sent','Failed')),
 attempts INTEGER NOT NULL DEFAULT 0,
 retry_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 sent_at TIMESTAMPTZ,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
