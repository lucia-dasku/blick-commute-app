CREATE TABLE IF NOT EXISTS google_play_purchases (
  token_fingerprint CHAR(64) PRIMARY KEY,
  product_id TEXT,
  order_id TEXT,
  purchase_state TEXT NOT NULL CHECK (purchase_state IN ('PURCHASED', 'PENDING', 'CANCELLED', 'UNKNOWN')),
  acknowledgement_state TEXT NOT NULL CHECK (acknowledgement_state IN ('PENDING', 'ACKNOWLEDGED', 'UNKNOWN')),
  purchase_completion_time TIMESTAMPTZ,
  quantity INTEGER CHECK (quantity IS NULL OR quantity >= 0),
  refundable_quantity INTEGER CHECK (refundable_quantity IS NULL OR refundable_quantity >= 0),
  consumption_state TEXT,
  entitlement_active BOOLEAN NOT NULL DEFAULT FALSE,
  voided BOOLEAN NOT NULL DEFAULT FALSE,
  last_verified_at TIMESTAMPTZ NOT NULL,
  last_event_time TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS google_play_purchases_order_id_idx
  ON google_play_purchases (order_id)
  WHERE order_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS google_play_purchases_inactive_retention_idx
  ON google_play_purchases (updated_at)
  WHERE entitlement_active = FALSE;

CREATE TABLE IF NOT EXISTS google_play_rtdn_messages (
  message_id TEXT PRIMARY KEY,
  publish_time TIMESTAMPTZ,
  status TEXT NOT NULL CHECK (status IN ('PROCESSING', 'PROCESSED', 'FAILED')),
  processing_started_at TIMESTAMPTZ NOT NULL,
  processed_at TIMESTAMPTZ,
  attempt_count INTEGER NOT NULL DEFAULT 1 CHECK (attempt_count > 0),
  last_failure_code TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS google_play_rtdn_messages_retention_idx
  ON google_play_rtdn_messages (processed_at)
  WHERE status = 'PROCESSED';
