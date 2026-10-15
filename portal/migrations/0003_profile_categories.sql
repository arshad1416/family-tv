-- Category names a profile's TVs last reported, per kind (live, movie, series), so the admin page
-- can arrange and hide them. The chosen order itself lives in the profile's encrypted data.
CREATE TABLE profile_categories (
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  names_json TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (profile_id, kind)
);
