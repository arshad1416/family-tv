-- Anonymous feature use from TVs on profiles with "Usage stats (anonymous)" on: which screens and
-- buttons get used, never what was watched or searched. Rows older than 30 days are deleted as new
-- ones arrive.
CREATE TABLE usage_events (
  id INTEGER PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  app TEXT NOT NULL,               -- kotlin or rust
  app_version TEXT,
  at INTEGER NOT NULL,             -- ms, the TV's clock capped at ours
  screen TEXT NOT NULL,
  action TEXT NOT NULL,
  value TEXT                       -- a number, or one of the fixed words in src/usage.ts
);
CREATE INDEX usage_events_at ON usage_events (at);
