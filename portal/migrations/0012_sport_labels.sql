-- What sport a guide programme shows, as Jev answered it for the TVs' Sports tab. Shared by every
-- TV so each programme title is asked once; also the labelled set for training a local model later.
CREATE TABLE IF NOT EXISTS sport_labels (
  key TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  genres TEXT,
  label TEXT NOT NULL,
  p REAL,
  source TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
