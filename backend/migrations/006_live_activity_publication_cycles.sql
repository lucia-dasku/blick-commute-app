CREATE TABLE IF NOT EXISTS live_activity_publication_cycle_control (
  scope TEXT PRIMARY KEY CHECK (scope = 'GLOBAL'),
  last_fence_generation BIGINT NOT NULL DEFAULT 0
    CHECK (last_fence_generation BETWEEN 0 AND 9007199254740991),
  current_slot_start_epoch_seconds BIGINT
    CHECK (
      current_slot_start_epoch_seconds IS NULL
      OR current_slot_start_epoch_seconds BETWEEN 0 AND 9007199254740991
    ),
  current_slot_cadence_seconds INTEGER
    CHECK (
      current_slot_cadence_seconds IS NULL
      OR current_slot_cadence_seconds > 0
    ),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CHECK (updated_at >= created_at),
  CHECK (
    (current_slot_start_epoch_seconds IS NULL) =
      (current_slot_cadence_seconds IS NULL)
  ),
  CHECK (
    current_slot_start_epoch_seconds IS NULL
    OR MOD(
      current_slot_start_epoch_seconds,
      current_slot_cadence_seconds
    ) = 0
  )
);

INSERT INTO live_activity_publication_cycle_control (
  scope,
  last_fence_generation,
  current_slot_start_epoch_seconds,
  current_slot_cadence_seconds,
  created_at,
  updated_at
) VALUES (
  'GLOBAL',
  0,
  NULL,
  NULL,
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP
)
ON CONFLICT (scope) DO NOTHING;

CREATE TABLE IF NOT EXISTS live_activity_publication_cycles (
  cycle_id UUID PRIMARY KEY,
  scope TEXT NOT NULL DEFAULT 'GLOBAL' CHECK (scope = 'GLOBAL'),
  slot_start_epoch_seconds BIGINT NOT NULL
    CHECK (slot_start_epoch_seconds BETWEEN 0 AND 9007199254740991),
  slot_cadence_seconds INTEGER NOT NULL CHECK (slot_cadence_seconds > 0),
  fence_generation BIGINT NOT NULL UNIQUE
    CHECK (fence_generation BETWEEN 1 AND 9007199254740991),
  state TEXT NOT NULL
    CHECK (
      state IN (
        'CLAIMED',
        'RUNNING',
        'COMPLETED',
        'NO_WORK',
        'FAILED',
        'ABANDONED'
      )
    ),
  claimed_at TIMESTAMPTZ NOT NULL,
  started_at TIMESTAMPTZ,
  lease_expires_at TIMESTAMPTZ NOT NULL,
  finalized_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL,
  failure_code TEXT
    CHECK (
      failure_code IS NULL
      OR failure_code IN (
        'WORKER_FAILED',
        'PUBLICATION_HISTORY_UNAVAILABLE',
        'LEASE_EXPIRED'
      )
    ),
  acquisition_count BIGINT
    CHECK (acquisition_count BETWEEN 0 AND 9007199254740991),
  publication_outcome_count BIGINT
    CHECK (publication_outcome_count BETWEEN 0 AND 9007199254740991),
  ready_publication_group_count BIGINT
    CHECK (ready_publication_group_count BETWEEN 0 AND 9007199254740991),
  binding_count BIGINT
    CHECK (binding_count BETWEEN 0 AND 9007199254740991),
  send_decision_count BIGINT
    CHECK (send_decision_count BETWEEN 0 AND 9007199254740991),
  no_push_decision_count BIGINT
    CHECK (no_push_decision_count BETWEEN 0 AND 9007199254740991),
  deferral_decision_count BIGINT
    CHECK (deferral_decision_count BETWEEN 0 AND 9007199254740991),
  dispatch_requested_count BIGINT
    CHECK (dispatch_requested_count BETWEEN 0 AND 9007199254740991),
  network_attempted_count BIGINT
    CHECK (network_attempted_count BETWEEN 0 AND 9007199254740991),
  dispatch_recorded_count BIGINT
    CHECK (dispatch_recorded_count BETWEEN 0 AND 9007199254740991),
  dispatch_not_reserved_count BIGINT
    CHECK (dispatch_not_reserved_count BETWEEN 0 AND 9007199254740991),
  dispatch_not_sent_count BIGINT
    CHECK (dispatch_not_sent_count BETWEEN 0 AND 9007199254740991),
  dispatch_result_not_recorded_count BIGINT
    CHECK (dispatch_result_not_recorded_count BETWEEN 0 AND 9007199254740991),
  dispatch_call_failed_count BIGINT
    CHECK (dispatch_call_failed_count BETWEEN 0 AND 9007199254740991),
  CONSTRAINT live_activity_pub_cycles_control_fk
    FOREIGN KEY (scope)
    REFERENCES live_activity_publication_cycle_control (scope),
  CHECK (MOD(slot_start_epoch_seconds, slot_cadence_seconds) = 0),
  CHECK (lease_expires_at > claimed_at),
  CHECK (started_at IS NULL OR started_at >= claimed_at),
  CHECK (finalized_at IS NULL OR finalized_at >= claimed_at),
  CHECK (updated_at >= claimed_at),
  CHECK (
    (state = 'CLAIMED' AND started_at IS NULL)
    OR state <> 'CLAIMED'
  ),
  CHECK (
    (state = 'RUNNING' AND started_at IS NOT NULL)
    OR state <> 'RUNNING'
  ),
  CHECK (
    (state IN ('CLAIMED', 'RUNNING') AND lease_expires_at > updated_at)
    OR state NOT IN ('CLAIMED', 'RUNNING')
  ),
  CHECK (
    (
      state IN ('CLAIMED', 'RUNNING')
      AND finalized_at IS NULL
      AND failure_code IS NULL
    )
    OR (
      state IN ('COMPLETED', 'NO_WORK')
      AND finalized_at IS NOT NULL
      AND failure_code IS NULL
    )
    OR (
      state = 'FAILED'
      AND finalized_at IS NOT NULL
      AND failure_code IN ('WORKER_FAILED', 'PUBLICATION_HISTORY_UNAVAILABLE')
    )
    OR (
      state = 'ABANDONED'
      AND finalized_at IS NOT NULL
      AND failure_code = 'LEASE_EXPIRED'
    )
  ),
  CHECK (
    NUM_NONNULLS(
      acquisition_count,
      publication_outcome_count,
      ready_publication_group_count,
      binding_count,
      send_decision_count,
      no_push_decision_count,
      deferral_decision_count,
      dispatch_requested_count,
      network_attempted_count,
      dispatch_recorded_count,
      dispatch_not_reserved_count,
      dispatch_not_sent_count,
      dispatch_result_not_recorded_count,
      dispatch_call_failed_count
    ) IN (0, 14)
  ),
  CHECK (
    (
      state IN ('CLAIMED', 'RUNNING', 'ABANDONED')
      AND acquisition_count IS NULL
    )
    OR (
      state IN ('COMPLETED', 'NO_WORK')
      AND acquisition_count IS NOT NULL
    )
    OR state = 'FAILED'
  ),
  CHECK (
    binding_count IS NULL
    OR send_decision_count + no_push_decision_count + deferral_decision_count =
      binding_count
  ),
  CHECK (
    dispatch_requested_count IS NULL
    OR dispatch_recorded_count + dispatch_not_reserved_count +
      dispatch_not_sent_count + dispatch_result_not_recorded_count +
      dispatch_call_failed_count = dispatch_requested_count
  ),
  CHECK (
    dispatch_requested_count IS NULL
    OR network_attempted_count <= dispatch_requested_count
  ),
  CHECK (
    state <> 'NO_WORK'
    OR (
      acquisition_count = 0
      AND publication_outcome_count = 0
      AND ready_publication_group_count = 0
      AND binding_count = 0
      AND send_decision_count = 0
      AND no_push_decision_count = 0
      AND deferral_decision_count = 0
      AND dispatch_requested_count = 0
      AND network_attempted_count = 0
      AND dispatch_recorded_count = 0
      AND dispatch_not_reserved_count = 0
      AND dispatch_not_sent_count = 0
      AND dispatch_result_not_recorded_count = 0
      AND dispatch_call_failed_count = 0
    )
  )
);

CREATE UNIQUE INDEX IF NOT EXISTS live_activity_pub_cycles_active_scope_idx
  ON live_activity_publication_cycles (scope)
  WHERE state IN ('CLAIMED', 'RUNNING');

CREATE INDEX IF NOT EXISTS live_activity_pub_cycles_slot_history_idx
  ON live_activity_publication_cycles (
    scope,
    slot_start_epoch_seconds,
    slot_cadence_seconds,
    fence_generation DESC
  );
