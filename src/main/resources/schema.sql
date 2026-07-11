CREATE TABLE IF NOT EXISTS "person" (
    "id" BIGSERIAL PRIMARY KEY,
    "name" VARCHAR(255) NOT NULL,
    "email" VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS "bike" (
    "id" UUID PRIMARY KEY,
    "code" TEXT NOT NULL,
    "status" TEXT  NOT NULL
);

CREATE TABLE IF NOT EXISTS "reservation" (
    "id" UUID PRIMARY KEY,
    "bike_id" UUID NOT NULL REFERENCES "bike"("id"),
    "status" TEXT NOT NULL,
    "created_at" TIMESTAMPTZ NOT NULL
);
