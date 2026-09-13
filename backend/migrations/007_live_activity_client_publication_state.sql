CREATE TABLE IF NOT EXISTS live_activity_client_publication_state (
  installation_id TEXT PRIMARY KEY,
  capability TEXT NOT NULL
    CHECK (
      capability IN (
        'DIRECT_LEGACY',
        'DIRECT_IOS18',
        'BROADCAST_CAPABLE',
        'UNKNOWN'
      )
    ),
  frequent_pushes TEXT NOT NULL
    CHECK (frequent_pushes IN ('ENABLED', 'DISABLED', 'UNKNOWN')),
  locale TEXT NOT NULL CHECK (locale IN ('en', 'sv')),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT live_activity_client_state_installation_fk
    FOREIGN KEY (installation_id)
    REFERENCES live_commute_installations (installation_id)
    ON DELETE CASCADE,
  CHECK (updated_at >= created_at)
);
