-- "Lock this TV" on the admin page: while set, the TV plays nothing and shows this message
-- full screen. Empty means unlocked. The TV keeps the last state it heard, so a restart or an
-- offline start stays locked.
ALTER TABLE devices ADD COLUMN lock_message TEXT;
