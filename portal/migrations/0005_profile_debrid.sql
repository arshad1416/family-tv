-- One Real-Debrid account per profile, linked from any of its TVs and shared with the rest.
-- The portal keeps the credentials and refreshes the access token, so TVs never race each other.
CREATE TABLE profile_debrid (
  profile_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
  data_enc TEXT NOT NULL,          -- AES-GCM encrypted JSON: client id/secret, access and refresh tokens
  username TEXT,
  expires_at INTEGER NOT NULL,     -- when the access token runs out (ms)
  linked_by TEXT,                  -- device id that signed in
  updated_at INTEGER NOT NULL
);
