CREATE TABLE profiles (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  data_enc TEXT NOT NULL,          -- AES-GCM encrypted JSON: sources, parental PIN, hidden categories
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  pair_code TEXT,
  pair_expires INTEGER,
  nickname TEXT,
  profile_id TEXT REFERENCES profiles(id) ON DELETE SET NULL,
  manufacturer TEXT,
  model TEXT,
  sdk INTEGER,
  app_version TEXT,
  country TEXT,
  last_seen INTEGER,
  status_json TEXT,                -- last heartbeat payload (what is playing, buffering, last error)
  created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX devices_pair_code ON devices(pair_code) WHERE pair_code IS NOT NULL;

CREATE TABLE commands (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  type TEXT NOT NULL,
  payload TEXT,
  created_at INTEGER NOT NULL,
  delivered_at INTEGER,
  acked_at INTEGER,
  result TEXT
);
CREATE INDEX commands_pending ON commands(device_id, acked_at);

CREATE TABLE diagnostics (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  verdict TEXT NOT NULL,
  report_json TEXT NOT NULL
);
CREATE INDEX diagnostics_device ON diagnostics(device_id, created_at);
