# Concurrency Hints

Concepts to reach for at each level. Try to hit the problem naively first (and watch it fail under load), then fix it with the concept listed.

## Level 1 — Reserve
- Naive `SELECT status` then `UPDATE` in two steps → lost update. Reproduce with a JMeter/`ExecutorService` burst of parallel requests on one bike.
- Fixes to compare: `UPDATE ... WHERE status = 'AVAILABLE'` (atomic conditional update, check affected-row count) vs `SELECT ... FOR UPDATE` vs optimistic locking (`version` column + retry on 0 rows updated).
- DB isolation level matters here: READ COMMITTED (Postgres default) still allows this race — the fix is row locking or atomic predicate, not isolation level alone.

## Level 2 — Cancel
- Guard with the same conditional-update pattern: `UPDATE reservations SET status='CANCELLED' WHERE id=? AND status='ACTIVE'`.
- Idempotency: cancelling twice concurrently should not double-release the bike — check affected rows before touching the bike row.

## Level 3 — Release / state machine
- Model transitions explicitly (enum + allowed-transition map), enforce in the SQL predicate, not just in Java — Java-side checks are racy without a lock.
- Look at `SELECT ... FOR UPDATE` vs `UPDATE ... WHERE state = X` for the reservation row before touching bike state.

## Level 4 — Station capacity
- Two-row check-then-act (station count + bike) is the TOCTOU trap. Options: lock station row first (`SELECT FOR UPDATE`) to serialize, or maintain a counter column updated atomically (`UPDATE stations SET available = available - 1 WHERE available > 0`).
- Compare pessimistic locking (`FOR UPDATE`) vs a single atomic `UPDATE ... WHERE` with no separate SELECT.

## Level 5 — Expiry
- Background thread (`ScheduledExecutorService`) racing with request threads on the same row.
- Same conditional-update pattern protects you: expiry job does `UPDATE ... WHERE status='RESERVED' AND created_at < now() - interval`. Whoever's UPDATE affects 0 rows lost the race — handle that as a normal "too late" response, not an error.
- Postgres `SELECT ... FOR UPDATE SKIP LOCKED` is worth learning here for job-queue-style processing without blocking.

## Level 6 — Waitlist
- FIFO ordering under concurrent inserts: don't rely on `ORDER BY created_at` alone (ties, clock skew) — use a sequence/serial column for strict order.
- Assigning a freed bike to "the next" waitlister is another check-then-act: lock the waitlist head row, or use `SKIP LOCKED` to hand off safely between competing workers.

## Level 7 — Batch reservation
- Multi-row transaction, must lock rows in a **consistent order** (e.g. sort bike IDs before locking) to avoid deadlocks when two batch requests overlap on bikes.
- Learn to read a Postgres deadlock error and reproduce one on purpose before fixing it.
- Compare "lock all, then check all" vs "lock and check one at a time with early abort."

## Level 8 — Transfer
- Cross-aggregate transaction (bike + two stations). Same lock-ordering discipline as Level 7.
- Decide and justify your isolation level (READ COMMITTED vs REPEATABLE READ vs SERIALIZABLE) — try SERIALIZABLE and handle serialization-failure retries.

## Level 9 — Rate limiting
- In-JVM counter (`AtomicInteger`/`ConcurrentHashMap`) works only single-instance — call this out explicitly as a limitation before Level 10.
- DB-backed counter needs the same atomic-update discipline as Level 4.

## Level 10 — Scale-out
- Anything kept in JVM memory (locks, counters, caches) breaks the moment you run 2 instances — this level exists to make you feel that pain.
- Move coordination to the DB (advisory locks: `pg_advisory_lock`) or an external store (Redis `SETNX`/Redisson locks).

## Level 11 — Read-heavy
- Measure read/write contention with `pg_stat_activity` / lock wait metrics.
- Trade-offs to actually compare: read replica lag, cache invalidation on write, snapshot isolation for consistent-enough reads without blocking writers.

## General tools to use throughout
- Load-test races with a simple `ExecutorService` + `CountDownLatch` harness in a JUnit test — don't just eyeball it, assert exactly one winner.
- Always check `UPDATE` affected-row counts instead of trusting a prior `SELECT`.
- Use Testcontainers (already wired in this repo) to run real concurrent-transaction tests against actual Postgres, not H2/mocks — locking behavior differs.
