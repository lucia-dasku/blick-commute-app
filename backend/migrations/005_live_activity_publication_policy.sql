ALTER TABLE live_activity_direct_dispatch_attempts
  ADD COLUMN IF NOT EXISTS visible_content_fingerprint CHAR(64),
  ADD COLUMN IF NOT EXISTS publication_source_fetched_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS publication_stale_at TIMESTAMPTZ;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conrelid = 'live_activity_direct_dispatch_attempts'::regclass
      AND conname = 'live_activity_direct_dispatch_attempts_publication_metadata_check'
  ) THEN
    ALTER TABLE live_activity_direct_dispatch_attempts
      ADD CONSTRAINT live_activity_direct_dispatch_attempts_publication_metadata_check
      CHECK (
        (
          visible_content_fingerprint IS NULL
          AND publication_source_fetched_at IS NULL
          AND publication_stale_at IS NULL
        )
        OR (
          visible_content_fingerprint IS NOT NULL
          AND visible_content_fingerprint ~ '^[0-9a-f]{64}$'
          AND publication_source_fetched_at IS NOT NULL
          AND (
            publication_stale_at IS NULL
            OR publication_stale_at >= publication_source_fetched_at
          )
        )
      );
  END IF;
END
$$;

CREATE INDEX IF NOT EXISTS live_activity_direct_dispatch_accepted_publication_idx
  ON live_activity_direct_dispatch_attempts (
    binding_id,
    installation_id,
    session_revision,
    event_timestamp DESC
  )
  WHERE state = 'ACCEPTED';
