-- Account owner's own devices only. Removal keeps a revision tombstone so old writes cannot restore a key.
CREATE TABLE profile_rd_api_keys (
  profile_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
  revision INTEGER NOT NULL CHECK (revision > 0),
  key_enc TEXT,
  updated_at INTEGER NOT NULL
);
