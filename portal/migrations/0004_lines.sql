-- Every 8K panel line the portal knows, in a profile or not (the panel's API can't list lines),
-- with what the panel last said about it. Takes over from line_status, which only covered lines
-- inside profiles and is no longer read.
CREATE TABLE lines (
  username TEXT PRIMARY KEY,
  data_enc TEXT NOT NULL,          -- AES-GCM encrypted JSON: name, server URLs, password
  expire TEXT,                     -- YYYY-MM-DD
  enabled INTEGER,
  max_connections INTEGER,
  note TEXT,
  error TEXT,                      -- why the last check failed, if it did
  checked_at INTEGER,
  created_at INTEGER NOT NULL
);
