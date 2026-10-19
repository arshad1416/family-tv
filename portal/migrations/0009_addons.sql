-- Paid add-ons: features already built into the TV apps that the portal switches on for a profile
-- (parity checklist, "Spec: Paid add-ons framework"). There is no checkout yet: purchases are
-- granted and revoked by hand on the admin page's Add-ons tab. No price until one is chosen.
CREATE TABLE addons (
  id TEXT PRIMARY KEY,             -- the slug the apps know it by, e.g. multiview
  name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'draft',  -- draft (hidden from TVs), available, retired (no longer sold; owners keep it)
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

-- One row per profile and add-on: a purchase covers every TV on the profile.
CREATE TABLE addon_purchases (
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  addon_id TEXT NOT NULL REFERENCES addons(id),
  status TEXT NOT NULL,            -- owned, refunded, revoked
  source TEXT NOT NULL,            -- manual, helcim, play, appstore
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (profile_id, addon_id)
);
