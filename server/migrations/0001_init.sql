-- Accounts directory: only what sign-in needs. Everything an account syncs lives in its own Durable Object.
CREATE TABLE users (
  id TEXT PRIMARY KEY,
  google_sub TEXT UNIQUE,
  email TEXT NOT NULL,
  name TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX users_email ON users(email);

-- Invite only: an email must be here before its first sign-in.
CREATE TABLE invites (
  email TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  used_by TEXT
);
