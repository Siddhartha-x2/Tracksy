CREATE TABLE user_github_identities (
 user_id BIGINT PRIMARY KEY REFERENCES users(id),
 github_id BIGINT NOT NULL UNIQUE,
 github_username VARCHAR(39) NOT NULL,
 verified_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE github_oauth_states (
 state_hash CHAR(64) PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES users(id),
 session_hash CHAR(64) NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX github_state_expiry ON github_oauth_states(expires_at);
