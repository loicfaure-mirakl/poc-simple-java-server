# Bike Reservation — Feature Roadmap

Each level adds a feature and a new concurrency failure mode. Build them in order; don't skip.

## Level 0 — Setup
- `Bike` (id, code, status: AVAILABLE/RESERVED/IN_USE), `Reservation` (id, bike_id, user_id, status, created_at).
- CRUD only, single-threaded. No concurrency yet — this is the baseline.

## Level 1 — Reserve a bike
- `POST /bikes/{id}/reserve`: only succeeds if the bike is AVAILABLE.
- Fire N concurrent requests at the same bike → exactly one must win.
- Concurrency problem: **lost update / race condition** on a read-then-write.

## Level 2 — Cancel a reservation
- `POST /reservations/{id}/cancel`: bike goes back to AVAILABLE.
- Concurrency problem: **cancel racing with reserve** — cancelling an already-used or already-cancelled reservation.

## Level 3 — Release (return the bike)
- `POST /reservations/{id}/release`: reservation → RETURNED, bike → AVAILABLE.
- Introduce a state machine (AVAILABLE → RESERVED → IN_USE → AVAILABLE).
- Concurrency problem: **invalid state transitions** under concurrent calls (release before pickup, double release).

## Level 4 — Limited bike pool per station
- Bikes belong to a `Station` with a fixed capacity. Reservation must check station has an available bike.
- Concurrency problem: **check-then-act across two rows** (station count + bike row) — classic TOCTOU.

## Level 5 — Reservation expiry
- Reservations auto-expire after N seconds if not picked up (background job / scheduler).
- Concurrency problem: **race between expiry job and user action** (user picks up bike exactly as it expires).

## Level 6 — Waitlist
- When no bike is available, users can join a waitlist; first in line gets notified/auto-assigned when one frees up.
- Concurrency problem: **ordering guarantees under contention**, notify-then-race (two waitlisted users both try to grab the freed bike).

## Level 7 — Multi-bike booking (batch reservation)
- A single request reserves N bikes atomically (e.g. for a group ride) — all or nothing.
- Concurrency problem: **multi-row atomicity**, **deadlocks** from inconsistent lock ordering across concurrent batch requests.

## Level 8 — Transfer between stations
- Move a bike from one station to another (maintenance van), competing with reservations at both stations.
- Concurrency problem: **cross-aggregate transactions**, lock ordering, isolation level choice.

## Level 9 — Rate limiting / fairness
- Cap reservations per user per time window (stop one user from hoarding bikes).
- Concurrency problem: **shared counter contention**, in-memory vs DB-backed counters, distributed rate limiting if you scale to >1 instance.

## Level 10 — Horizontal scale-out
- Run 2+ app instances behind a load balancer, same DB.
- Concurrency problem: everything above, but now **in-memory locks/caches don't work** — forces DB-level or distributed locking (advisory locks, Redis, etc).

## Level 11 — Read-heavy dashboard
- `GET /stations/{id}/availability` hit far more often than writes; must stay fast and consistent-enough.
- Concurrency problem: **read/write contention**, choosing isolation level vs read replicas vs caching with staleness trade-offs.
