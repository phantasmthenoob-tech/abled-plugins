-- Medieval Era schema, version 001.
--
-- Timestamps are stored as epoch milliseconds (UTC) so they are unaffected by server locale.
-- Player identity is always the UUID; names are stored for display and for admin lookups only.
--
-- Only tables the implemented systems use are created here. wars, sieges and structures get their
-- own migrations when those subsystems are implemented.

CREATE TABLE players (
    uuid       TEXT    PRIMARY KEY,
    name       TEXT    NOT NULL,
    first_seen INTEGER NOT NULL,
    last_seen  INTEGER NOT NULL
);

CREATE INDEX idx_players_name ON players (name COLLATE NOCASE);

CREATE TABLE kingdoms (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL,
    tag        TEXT    NOT NULL,
    founder_id TEXT    NOT NULL,
    created_at INTEGER NOT NULL
);

-- Kingdom names are unique case-insensitively: two kingdoms called Avalon would be ambiguous.
CREATE UNIQUE INDEX idx_kingdoms_name ON kingdoms (name COLLATE NOCASE);

CREATE TABLE kingdom_members (
    kingdom_id  INTEGER NOT NULL REFERENCES kingdoms (id) ON DELETE CASCADE,
    player_uuid TEXT    NOT NULL,
    rank        TEXT    NOT NULL,
    joined_at   INTEGER NOT NULL,
    PRIMARY KEY (kingdom_id, player_uuid)
);

-- A player belongs to at most one kingdom.
CREATE UNIQUE INDEX idx_kingdom_members_player ON kingdom_members (player_uuid);

CREATE TABLE claims (
    world      TEXT    NOT NULL,
    chunk_x    INTEGER NOT NULL,
    chunk_z    INTEGER NOT NULL,
    kingdom_id INTEGER NOT NULL REFERENCES kingdoms (id) ON DELETE CASCADE,
    claimed_at INTEGER NOT NULL,
    PRIMARY KEY (world, chunk_x, chunk_z)
);

CREATE INDEX idx_claims_kingdom ON claims (kingdom_id);

CREATE TABLE deathbans (
    uuid       TEXT    PRIMARY KEY,
    death_at   INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    cause      TEXT    NOT NULL,
    killer     TEXT    NOT NULL
);

CREATE INDEX idx_deathbans_expires ON deathbans (expires_at);

CREATE TABLE world_state (
    state_key   TEXT PRIMARY KEY,
    state_value TEXT NOT NULL
);
