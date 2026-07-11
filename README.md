# simple-javalin-app

Javalin + jOOQ + PostgreSQL + Jackson + Logback (SLF4J) POC, with tests wired up (JUnit 5,
javalin-testtools, AssertJ, Testcontainers).

## Goal

Apply CQRS and SOLID principles in a simple CRUD app.

## Build

jOOQ sources are generated from `src/main/resources/schema.sql` into `target/generated-sources/jooq`
(via jOOQ's `DDLDatabase`, which parses the schema file directly — no live database needed at build
time) and must exist before the main code compiles. Regenerate them with:

```
mvn clean
mvn package -Pjooq-gen
```

Once generated, they survive in `target/` until the next `clean`, so day-to-day you can just run:

```
mvn test
mvn package
```

## Test

Tests spin up a real PostgreSQL instance via Testcontainers (one container shared per test class),
so Docker must be running. No other setup is needed — `mvn test` handles it.

## Run

Requires a running PostgreSQL instance. For local dev, start the provided `docker-compose.yml`:

```
docker compose up -d
java -jar target/simple-javalin-app.jar
```

The container's Postgres always listens on `5432` internally; `5455` is just the host-side port
(picked to avoid clashing with other local Postgres containers — change it and `db.url` together
if it collides with something else on your machine).

Connects to `jdbc:postgresql://localhost:5455/simple_javalin_app` by default (override with
`-Ddb.url=... -Ddb.user=... -Ddb.password=...`) and starts on port 7070 (override with
`-Dserver.port=...`).

- `GET /health`
- `GET /persons`
- `GET /persons/{id}`
- `POST /persons` with body `{"name": "...", "email": "..."}`
