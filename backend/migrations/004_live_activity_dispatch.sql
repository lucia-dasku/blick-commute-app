CREATE UNIQUE INDEX IF NOT EXISTS live_activity_push_to_start_tokens_dispatch_generation_idx
  ON live_activity_push_to_start_tokens (
    installation_id,
    server_revision,
    client_generation,
    apns_environment
  );

CREATE UNIQUE INDEX IF NOT EXISTS live_activity_update_tokens_dispatch_generation_idx
  ON live_activity_update_tokens (
    binding_id,
    server_revision,
    client_generation,
    apns_environment
  );

CREATE UNIQUE INDEX IF NOT EXISTS live_activity_delivery_bindings_dispatch_identity_idx
  ON live_activity_delivery_bindings (
    binding_id,
    installation_id,
    delivery_strategy,
    session_revision
  );

CREATE TABLE IF NOT EXISTS live_activity_direct_dispatch_state (
  binding_id UUID PRIMARY KEY,
  installation_id TEXT NOT NULL,
  binding_strategy TEXT NOT NULL DEFAULT 'DIRECT_TOKEN'
    CHECK (binding_strategy = 'DIRECT_TOKEN'),
  session_revision INTEGER NOT NULL
    CHECK (session_revision BETWEEN 1 AND 2147483647),
  last_reserved_event_timestamp BIGINT NOT NULL
    CHECK (
      last_reserved_event_timestamp BETWEEN 0 AND 9007199254740991
    ),
  terminal_intent_event_timestamp BIGINT
    CHECK (
      terminal_intent_event_timestamp IS NULL
      OR terminal_intent_event_timestamp BETWEEN 0 AND 9007199254740991
    ),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT live_activity_direct_dispatch_state_binding_fk
    FOREIGN KEY (
      binding_id,
      installation_id,
      binding_strategy,
      session_revision
    )
    REFERENCES live_activity_delivery_bindings (
      binding_id,
      installation_id,
      delivery_strategy,
      session_revision
    )
    ON DELETE CASCADE,
  CONSTRAINT live_activity_direct_dispatch_state_identity_key
    UNIQUE (binding_id, installation_id, session_revision),
  CHECK (updated_at >= created_at),
  CHECK (
    terminal_intent_event_timestamp IS NULL
    OR terminal_intent_event_timestamp <= last_reserved_event_timestamp
  )
);

CREATE TABLE IF NOT EXISTS live_activity_direct_dispatch_attempts (
  dispatch_id UUID PRIMARY KEY,
  binding_id UUID NOT NULL,
  installation_id TEXT NOT NULL,
  session_revision INTEGER NOT NULL
    CHECK (session_revision BETWEEN 1 AND 2147483647),
  operation_kind TEXT NOT NULL
    CHECK (operation_kind IN ('START', 'DIRECT_UPDATE', 'DIRECT_END')),
  event_timestamp BIGINT NOT NULL
    CHECK (event_timestamp BETWEEN 0 AND 9007199254740991),
  push_to_start_server_revision INTEGER,
  push_to_start_client_generation INTEGER,
  update_token_server_revision INTEGER,
  update_token_client_generation INTEGER,
  apns_environment TEXT NOT NULL
    CHECK (apns_environment IN ('SANDBOX', 'PRODUCTION')),
  apns_request_id UUID NOT NULL UNIQUE,
  payload_fingerprint CHAR(64)
    CHECK (
      payload_fingerprint IS NULL
      OR payload_fingerprint ~ '^[0-9a-f]{64}$'
    ),
  state TEXT NOT NULL
    CHECK (
      state IN (
        'RESERVED',
        'IN_FLIGHT',
        'ACCEPTED',
        'REJECTED',
        'RETRYABLE',
        'OUTCOME_UNKNOWN',
        'ABORTED',
        'SUPERSEDED'
      )
    ),
  apns_status SMALLINT
    CHECK (apns_status IS NULL OR apns_status BETWEEN 100 AND 599),
  apns_reason TEXT
    CHECK (
      apns_reason IS NULL
      OR (
        char_length(apns_reason) BETWEEN 1 AND 128
        AND apns_reason ~ '^[ -~]+$'
      )
    ),
  retry_advice TEXT
    CHECK (
      retry_advice IS NULL
      OR retry_advice IN (
        'NO_RETRY',
        'RETRY_AFTER_APPLE_BACKOFF',
        'RETRY_THROTTLED',
        'REFRESH_PROVIDER_TOKEN',
        'PERMANENT_DESTINATION_FAILURE',
        'PERMANENT_PAYLOAD_FAILURE',
        'OUTCOME_UNKNOWN',
        'OPERATOR_CONFIGURATION_REQUIRED'
      )
    ),
  retry_not_before TIMESTAMPTZ,
  post_send_authority TEXT NOT NULL DEFAULT 'NOT_CHECKED'
    CHECK (post_send_authority IN ('NOT_CHECKED', 'MATCHED', 'CHANGED')),
  token_invalidation_outcome TEXT NOT NULL DEFAULT 'NOT_APPLICABLE'
    CHECK (
      token_invalidation_outcome IN (
        'NOT_APPLICABLE',
        'INVALIDATED_EXACT_GENERATION',
        'GENERATION_NO_LONGER_CURRENT',
        'ALREADY_INVALIDATED'
      )
    ),
  created_at TIMESTAMPTZ NOT NULL,
  in_flight_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  CONSTRAINT live_activity_direct_dispatch_attempts_state_fk
    FOREIGN KEY (binding_id, installation_id, session_revision)
    REFERENCES live_activity_direct_dispatch_state (
      binding_id,
      installation_id,
      session_revision
    )
    ON DELETE CASCADE,
  CONSTRAINT live_activity_direct_dispatch_attempts_push_generation_fk
    FOREIGN KEY (
      installation_id,
      push_to_start_server_revision,
      push_to_start_client_generation,
      apns_environment
    )
    REFERENCES live_activity_push_to_start_tokens (
      installation_id,
      server_revision,
      client_generation,
      apns_environment
    ),
  CONSTRAINT live_activity_direct_dispatch_attempts_update_generation_fk
    FOREIGN KEY (
      binding_id,
      update_token_server_revision,
      update_token_client_generation,
      apns_environment
    )
    REFERENCES live_activity_update_tokens (
      binding_id,
      server_revision,
      client_generation,
      apns_environment
    ),
  CONSTRAINT live_activity_direct_dispatch_attempts_event_key
    UNIQUE (binding_id, event_timestamp),
  CHECK (
    (
      operation_kind = 'START'
      AND push_to_start_server_revision IS NOT NULL
      AND push_to_start_server_revision BETWEEN 1 AND 2147483647
      AND push_to_start_client_generation IS NOT NULL
      AND push_to_start_client_generation BETWEEN 1 AND 2147483647
      AND update_token_server_revision IS NULL
      AND update_token_client_generation IS NULL
    )
    OR (
      operation_kind IN ('DIRECT_UPDATE', 'DIRECT_END')
      AND push_to_start_server_revision IS NULL
      AND push_to_start_client_generation IS NULL
      AND update_token_server_revision IS NOT NULL
      AND update_token_server_revision BETWEEN 1 AND 2147483647
      AND update_token_client_generation IS NOT NULL
      AND update_token_client_generation BETWEEN 1 AND 2147483647
    )
  ),
  CHECK (
    (
      state = 'RESERVED'
      AND payload_fingerprint IS NULL
      AND in_flight_at IS NULL
      AND completed_at IS NULL
    )
    OR (
      state = 'IN_FLIGHT'
      AND payload_fingerprint IS NOT NULL
      AND in_flight_at IS NOT NULL
      AND completed_at IS NULL
    )
    OR (
      state IN ('ACCEPTED', 'REJECTED', 'RETRYABLE', 'OUTCOME_UNKNOWN')
      AND payload_fingerprint IS NOT NULL
      AND in_flight_at IS NOT NULL
      AND completed_at IS NOT NULL
    )
    OR (
      state = 'ABORTED'
      AND (
        (payload_fingerprint IS NULL AND in_flight_at IS NULL)
        OR (payload_fingerprint IS NOT NULL AND in_flight_at IS NOT NULL)
      )
      AND completed_at IS NOT NULL
    )
    OR (
      state = 'SUPERSEDED'
      AND payload_fingerprint IS NULL
      AND in_flight_at IS NULL
      AND completed_at IS NOT NULL
    )
  ),
  CHECK (
    (state IN ('RESERVED', 'IN_FLIGHT') AND retry_advice IS NULL)
    OR (state NOT IN ('RESERVED', 'IN_FLIGHT') AND retry_advice IS NOT NULL)
  ),
  CHECK (
    state NOT IN ('RESERVED', 'IN_FLIGHT')
    OR (
      apns_reason IS NULL
      AND retry_not_before IS NULL
      AND token_invalidation_outcome = 'NOT_APPLICABLE'
    )
  ),
  CHECK (
    (
      state = 'ACCEPTED'
      AND apns_status IS NOT NULL
      AND apns_status = 200
    )
    OR (
      state IN ('REJECTED', 'RETRYABLE')
      AND apns_status IS NOT NULL
    )
    OR (
      state IN ('RESERVED', 'IN_FLIGHT', 'ABORTED', 'SUPERSEDED')
      AND apns_status IS NULL
      AND apns_reason IS NULL
    )
    OR state = 'OUTCOME_UNKNOWN'
  ),
  CHECK (
    (state = 'ACCEPTED' AND post_send_authority IN ('MATCHED', 'CHANGED'))
    OR (state <> 'ACCEPTED' AND post_send_authority = 'NOT_CHECKED')
  ),
  CHECK (
    token_invalidation_outcome = 'NOT_APPLICABLE'
    OR state = 'REJECTED'
  ),
  CHECK (in_flight_at IS NULL OR in_flight_at >= created_at),
  CHECK (completed_at IS NULL OR completed_at >= created_at),
  CHECK (
    completed_at IS NULL
    OR in_flight_at IS NULL
    OR completed_at >= in_flight_at
  ),
  CHECK (retry_not_before IS NULL OR retry_not_before >= completed_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS live_activity_direct_dispatch_active_idx
  ON live_activity_direct_dispatch_attempts (binding_id)
  WHERE state IN ('RESERVED', 'IN_FLIGHT');

CREATE UNIQUE INDEX IF NOT EXISTS live_activity_direct_dispatch_start_blocker_idx
  ON live_activity_direct_dispatch_attempts (binding_id)
  WHERE operation_kind = 'START'
    AND state IN ('RESERVED', 'IN_FLIGHT', 'ACCEPTED', 'OUTCOME_UNKNOWN');

CREATE INDEX IF NOT EXISTS live_activity_direct_dispatch_history_idx
  ON live_activity_direct_dispatch_attempts (binding_id, event_timestamp DESC);

CREATE INDEX IF NOT EXISTS live_activity_direct_dispatch_in_flight_idx
  ON live_activity_direct_dispatch_attempts (in_flight_at)
  WHERE state = 'IN_FLIGHT';
