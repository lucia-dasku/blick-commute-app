CREATE TABLE IF NOT EXISTS live_commute_installations (
  installation_id TEXT PRIMARY KEY
    CHECK (
      installation_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
  credential_digest CHAR(64) NOT NULL UNIQUE
    CHECK (credential_digest ~ '^[0-9a-f]{64}$'),
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CHECK (updated_at >= created_at),
  CHECK (
    revoked_at IS NULL
    OR (revoked_at >= created_at AND revoked_at <= updated_at)
  )
);

CREATE TABLE IF NOT EXISTS live_commute_sessions (
  installation_id TEXT NOT NULL,
  session_id TEXT NOT NULL CHECK (char_length(session_id) BETWEEN 1 AND 256),
  routine_id TEXT NOT NULL CHECK (char_length(routine_id) BETWEEN 1 AND 256),
  starts_at TIMESTAMPTZ NOT NULL,
  ends_at TIMESTAMPTZ NOT NULL,
  query JSONB NOT NULL,
  lifecycle TEXT NOT NULL CHECK (lifecycle IN ('REGISTERED', 'CANCELLED')),
  revision INTEGER NOT NULL CHECK (revision > 0),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  cancelled_at TIMESTAMPTZ,
  PRIMARY KEY (installation_id, session_id),
  CONSTRAINT live_commute_sessions_installation_fk
    FOREIGN KEY (installation_id)
    REFERENCES live_commute_installations (installation_id)
    ON DELETE CASCADE,
  CHECK (starts_at < ends_at),
  CHECK (updated_at >= created_at),
  CHECK (jsonb_typeof(query) = 'object'),
  CHECK (query ? 'kind'),
  CHECK (query ->> 'kind' IN ('LINE_DIRECTION', 'EXACT_DESTINATION')),
  CHECK (
    (lifecycle = 'REGISTERED' AND cancelled_at IS NULL)
    OR (lifecycle = 'CANCELLED' AND cancelled_at IS NOT NULL)
  ),
  CHECK (
    cancelled_at IS NULL
    OR (cancelled_at >= created_at AND cancelled_at <= updated_at)
  )
);

CREATE INDEX IF NOT EXISTS live_commute_sessions_installation_window_idx
  ON live_commute_sessions (installation_id, starts_at, ends_at)
  WHERE lifecycle = 'REGISTERED';

CREATE INDEX IF NOT EXISTS live_commute_sessions_eligible_window_idx
  ON live_commute_sessions (starts_at, ends_at)
  WHERE lifecycle = 'REGISTERED';
