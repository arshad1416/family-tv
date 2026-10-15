-- Last known 8K panel state of each profile's Xtream line (see src/panel.ts), so pages show
-- days left without calling the panel every time.
CREATE TABLE line_status (
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  source_id TEXT NOT NULL,
  username TEXT NOT NULL,
  expire TEXT,                     -- YYYY-MM-DD
  enabled INTEGER,
  max_connections INTEGER,
  note TEXT,
  error TEXT,                      -- why the last check failed, if it did
  checked_at INTEGER NOT NULL,
  PRIMARY KEY (profile_id, source_id)
);
