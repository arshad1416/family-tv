-- Watched marks and resume points, shared by every TV in a profile. Each TV pushes the rows it
-- changed and pulls the ones changed since its last seq; the later updated_at wins.
CREATE TABLE profile_progress (
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  key TEXT NOT NULL,               -- m/<source>/<stream> or e/<source>/<series>/<season>/<episode>
  kind TEXT NOT NULL,              -- movie or episode
  source_id TEXT NOT NULL,
  item_id TEXT NOT NULL,
  season INTEGER NOT NULL DEFAULT 0,
  episode INTEGER NOT NULL DEFAULT 0,
  position_ms INTEGER NOT NULL DEFAULT 0,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  percent REAL NOT NULL DEFAULT 0,
  watched INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,     -- when the TV changed it (its clock, capped near ours)
  seq INTEGER NOT NULL,            -- per-profile change counter TVs pull from
  PRIMARY KEY (profile_id, key)
);
CREATE INDEX profile_progress_seq ON profile_progress (profile_id, seq);
