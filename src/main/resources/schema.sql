CREATE TABLE IF NOT EXISTS "person"
(
    "id"    BIGSERIAL PRIMARY KEY,
    "name"  VARCHAR(255) NOT NULL,
    "email" VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS "station"
(
    "id"              UUID PRIMARY KEY,
    "name"            TEXT NOT NULL,
    "capacity"        INT  NOT NULL,
    "available_count" INT  NOT NULL
);

CREATE TABLE IF NOT EXISTS "bike"
(
    "id"         UUID PRIMARY KEY,
    "code"       TEXT NOT NULL,
    "status"     TEXT NOT NULL,
    "station_id" UUID REFERENCES "station" ("id")
);

CREATE TABLE IF NOT EXISTS "reservation"
(
    "id"         UUID PRIMARY KEY,
    "bike_id"    UUID        NOT NULL REFERENCES "bike" ("id"),
    "status"     TEXT        NOT NULL,
    "created_at" TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS "waitlist_entry"
(
    "id"             UUID PRIMARY KEY,
    "seq"            INT GENERATED ALWAYS AS IDENTITY,
    "station_id"     UUID        NOT NULL REFERENCES "station" ("id"),
    "status"         TEXT        NOT NULL,
    "reservation_id" UUID REFERENCES "reservation" ("id"),
    "created_at"     TIMESTAMPTZ NOT NULL
);
