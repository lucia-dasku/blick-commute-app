CREATE TABLE IF NOT EXISTS live_activity_push_to_start_tokens (
  installation_id TEXT NOT NULL,
  server_revision INTEGER NOT NULL
    CHECK (server_revision BETWEEN 1 AND 2147483647),
  client_generation INTEGER NOT NULL
    CHECK (client_generation BETWEEN 1 AND 2147483647),
  apns_environment TEXT NOT NULL CHECK (apns_environment IN ('SANDBOX', 'PRODUCTION')),
  lifecycle TEXT NOT NULL CHECK (lifecycle IN ('CURRENT', 'REPLACED', 'INVALIDATED')),
  token_digest CHAR(64) NOT NULL CHECK (token_digest ~ '^[0-9a-f]{64}$'),
  token_ciphertext BYTEA NOT NULL
    CHECK (octet_length(token_ciphertext) BETWEEN 1 AND 4096),
  token_nonce BYTEA NOT NULL CHECK (octet_length(token_nonce) = 12),
  token_auth_tag BYTEA NOT NULL CHECK (octet_length(token_auth_tag) = 16),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  replaced_at TIMESTAMPTZ,
  invalidated_at TIMESTAMPTZ,
  PRIMARY KEY (installation_id, server_revision),
  CONSTRAINT live_activity_push_to_start_tokens_installation_fk
    FOREIGN KEY (installation_id)
    REFERENCES live_commute_installations (installation_id)
    ON DELETE CASCADE,
  CONSTRAINT live_activity_push_to_start_tokens_generation_key
    UNIQUE (installation_id, client_generation),
  CHECK (updated_at >= created_at),
  CHECK (
    (lifecycle = 'CURRENT' AND replaced_at IS NULL AND invalidated_at IS NULL)
    OR (lifecycle = 'REPLACED' AND replaced_at IS NOT NULL AND invalidated_at IS NULL)
    OR (lifecycle = 'INVALIDATED' AND replaced_at IS NULL AND invalidated_at IS NOT NULL)
  ),
  CHECK (
    replaced_at IS NULL
    OR (replaced_at >= created_at AND replaced_at <= updated_at)
  ),
  CHECK (
    invalidated_at IS NULL
    OR (invalidated_at >= created_at AND invalidated_at <= updated_at)
  )
);

CREATE UNIQUE INDEX IF NOT EXISTS live_activity_push_to_start_tokens_current_idx
  ON live_activity_push_to_start_tokens (installation_id)
  WHERE lifecycle = 'CURRENT';

CREATE TABLE IF NOT EXISTS live_activity_delivery_bindings (
  binding_id UUID PRIMARY KEY,
  installation_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  session_revision INTEGER NOT NULL CHECK (session_revision > 0),
  delivery_strategy TEXT NOT NULL
    CHECK (delivery_strategy IN ('DIRECT_TOKEN', 'BROADCAST_CHANNEL')),
  lifecycle TEXT NOT NULL
    CHECK (lifecycle IN ('PENDING_START', 'ENDED', 'INVALIDATED')),
  apple_activity_id TEXT
    CHECK (
      apple_activity_id IS NULL
      OR char_length(apple_activity_id) BETWEEN 1 AND 512
    ),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  ended_at TIMESTAMPTZ,
  invalidated_at TIMESTAMPTZ,
  CONSTRAINT live_activity_delivery_bindings_session_fk
    FOREIGN KEY (installation_id, session_id)
    REFERENCES live_commute_sessions (installation_id, session_id)
    ON DELETE CASCADE,
  CONSTRAINT live_activity_delivery_bindings_session_revision_key
    UNIQUE (installation_id, session_id, session_revision),
  CONSTRAINT live_activity_delivery_bindings_identity_strategy_key
    UNIQUE (binding_id, installation_id, delivery_strategy),
  CHECK (updated_at >= created_at),
  CHECK (
    (lifecycle = 'PENDING_START' AND ended_at IS NULL AND invalidated_at IS NULL)
    OR (lifecycle = 'ENDED' AND ended_at IS NOT NULL AND invalidated_at IS NULL)
    OR (lifecycle = 'INVALIDATED' AND ended_at IS NULL AND invalidated_at IS NOT NULL)
  ),
  CHECK (
    ended_at IS NULL
    OR (ended_at >= created_at AND ended_at <= updated_at)
  ),
  CHECK (
    invalidated_at IS NULL
    OR (invalidated_at >= created_at AND invalidated_at <= updated_at)
  )
);

CREATE TABLE IF NOT EXISTS live_activity_update_tokens (
  binding_id UUID NOT NULL,
  installation_id TEXT NOT NULL,
  binding_strategy TEXT NOT NULL DEFAULT 'DIRECT_TOKEN'
    CHECK (binding_strategy = 'DIRECT_TOKEN'),
  server_revision INTEGER NOT NULL
    CHECK (server_revision BETWEEN 1 AND 2147483647),
  client_generation INTEGER NOT NULL
    CHECK (client_generation BETWEEN 1 AND 2147483647),
  apns_environment TEXT NOT NULL CHECK (apns_environment IN ('SANDBOX', 'PRODUCTION')),
  lifecycle TEXT NOT NULL CHECK (lifecycle IN ('CURRENT', 'REPLACED', 'INVALIDATED')),
  token_digest CHAR(64) NOT NULL CHECK (token_digest ~ '^[0-9a-f]{64}$'),
  token_ciphertext BYTEA NOT NULL
    CHECK (octet_length(token_ciphertext) BETWEEN 1 AND 4096),
  token_nonce BYTEA NOT NULL CHECK (octet_length(token_nonce) = 12),
  token_auth_tag BYTEA NOT NULL CHECK (octet_length(token_auth_tag) = 16),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  replaced_at TIMESTAMPTZ,
  invalidated_at TIMESTAMPTZ,
  PRIMARY KEY (binding_id, server_revision),
  CONSTRAINT live_activity_update_tokens_binding_fk
    FOREIGN KEY (binding_id, installation_id, binding_strategy)
    REFERENCES live_activity_delivery_bindings (
      binding_id, installation_id, delivery_strategy
    )
    ON DELETE CASCADE,
  CONSTRAINT live_activity_update_tokens_generation_key
    UNIQUE (binding_id, client_generation),
  CHECK (updated_at >= created_at),
  CHECK (
    (lifecycle = 'CURRENT' AND replaced_at IS NULL AND invalidated_at IS NULL)
    OR (lifecycle = 'REPLACED' AND replaced_at IS NOT NULL AND invalidated_at IS NULL)
    OR (lifecycle = 'INVALIDATED' AND replaced_at IS NULL AND invalidated_at IS NOT NULL)
  ),
  CHECK (
    replaced_at IS NULL
    OR (replaced_at >= created_at AND replaced_at <= updated_at)
  ),
  CHECK (
    invalidated_at IS NULL
    OR (invalidated_at >= created_at AND invalidated_at <= updated_at)
  )
);

CREATE UNIQUE INDEX IF NOT EXISTS live_activity_delivery_bindings_activity_idx
  ON live_activity_delivery_bindings (installation_id, apple_activity_id)
  WHERE apple_activity_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS live_activity_update_tokens_current_idx
  ON live_activity_update_tokens (binding_id)
  WHERE lifecycle = 'CURRENT';
