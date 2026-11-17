-- Email + password sign-in. The device never sends the password: it sends a key derived from it (with the
-- email as salt, 300k PBKDF2 rounds), which is hashed again here.
ALTER TABLE users ADD COLUMN password_hash TEXT;
ALTER TABLE users ADD COLUMN password_salt TEXT;
ALTER TABLE users ADD COLUMN failed_logins INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until INTEGER NOT NULL DEFAULT 0;
