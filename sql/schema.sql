-- Database schema for the Smart Water Distribution system (SQLite).
-- Database.java runs these statements automatically when the program starts.

CREATE TABLE IF NOT EXISTS reservoirs (
    id        TEXT PRIMARY KEY,
    name      TEXT NOT NULL,
    capacity  REAL NOT NULL CHECK (capacity >= 0)            -- litres per day
);

CREATE TABLE IF NOT EXISTS demand_areas (
    id               TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    daily_demand     REAL NOT NULL CHECK (daily_demand >= 0), -- litres per day
    priority         TEXT NOT NULL CHECK (priority IN ('CRITICAL', 'HIGH', 'NORMAL')),
    area_type        TEXT NOT NULL DEFAULT 'RESIDENTIAL',     -- RESIDENTIAL or CRITICAL_INSTITUTION
    institution_type TEXT,                                    -- Hospital, Fire Station ... (institutions only)
    households       INTEGER NOT NULL DEFAULT 0               -- (residential areas only)
);

CREATE TABLE IF NOT EXISTS pipelines (
    id       TEXT PRIMARY KEY,
    node1    TEXT NOT NULL,                                   -- a reservoir id or an area id
    node2    TEXT NOT NULL,
    working  INTEGER NOT NULL DEFAULT 1                       -- 1 = working, 0 = failed
);

CREATE TABLE IF NOT EXISTS maintenance_requests (             -- the repair queue (first in, first out)
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    pipeline_id     TEXT NOT NULL,
    description     TEXT NOT NULL,
    status          TEXT NOT NULL,                            -- PENDING, IN_PROGRESS, DONE, CANCELLED
    created_time    TEXT NOT NULL,
    started_time    TEXT,
    completed_time  TEXT,
    created_by      TEXT
);

CREATE TABLE IF NOT EXISTS allocation_history (               -- a snapshot after every change
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    run_time           TEXT NOT NULL,
    day                INTEGER NOT NULL,                      -- the simulated day
    trigger_event      TEXT NOT NULL,                         -- what caused it, e.g. PIPELINE FAILED
    user_name          TEXT,
    total_available    REAL NOT NULL,
    total_allocated    REAL NOT NULL,
    total_unmet        REAL NOT NULL,
    disconnected_areas INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS allocation_details (               -- what each area received in a snapshot
    record_id  INTEGER NOT NULL,                              -- refers to allocation_history.id
    area_id    TEXT NOT NULL,
    area_name  TEXT NOT NULL,
    priority   TEXT NOT NULL,
    demand     REAL NOT NULL,
    allocated  REAL NOT NULL,
    status     TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS settings (                         -- small values, e.g. the simulated day
    key    TEXT PRIMARY KEY,
    value  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS events (                           -- history of everything that happens
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    event_time  TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    event_type  TEXT NOT NULL,
    details     TEXT NOT NULL,
    user_name   TEXT                                          -- who did it
);

CREATE TABLE IF NOT EXISTS users (                            -- people who can log in
    username       TEXT PRIMARY KEY,
    full_name      TEXT NOT NULL,
    role           TEXT NOT NULL CHECK (role IN ('OPERATOR', 'VIEWER')),
    salt           TEXT NOT NULL,                             -- random value added before hashing
    password_hash  TEXT NOT NULL                              -- passwords are never stored as plain text
);
