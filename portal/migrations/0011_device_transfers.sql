-- One-button move from the Rust app to the Kotlin app on the same TV: the Rust app asks for a code
-- (transfer-start), the Kotlin app claims it once (transfer-claim) and takes over the same device row
-- with a new token. Codes are kept hashed and the TV's own settings sealed; a row goes when claimed,
-- replaced or expired.
CREATE TABLE device_transfers (
  code_hash TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  local_enc TEXT,                  -- sealed JSON of the Rust app's local settings, or null
  expires INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX device_transfers_device ON device_transfers (device_id);

-- Claims with a wrong or spent code, per caller, for a short lockout. Kept an hour.
CREATE TABLE transfer_failures (
  ip TEXT NOT NULL,
  at INTEGER NOT NULL
);
CREATE INDEX transfer_failures_ip ON transfer_failures (ip, at);
