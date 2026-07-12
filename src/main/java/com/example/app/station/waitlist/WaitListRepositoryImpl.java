package com.example.app.station.waitlist;

import org.jooq.DSLContext;
import org.jooq.Record;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static com.example.app.jooq.generated.Tables.WAITLIST_ENTRY;

public class WaitListRepositoryImpl implements WaitListRepository {
    private final DSLContext dslContext;
    private final Clock clock;

    public WaitListRepositoryImpl(DSLContext dslContext, Clock clock) {
        this.dslContext = dslContext;
        this.clock = clock;
    }

    @Override
    public WaitList join(UUID stationId) {
        // no .set(WAITLIST_ENTRY.SEQ, ...) here — the column's own SERIAL default calls
        // Postgres's nextval() to assign it atomically, so concurrent joins can never collide
        // on the same seq the way a hand-computed MAX(seq)+1 would.
        return dslContext.insertInto(WAITLIST_ENTRY)
                .set(WAITLIST_ENTRY.ID, UUID.randomUUID())
                .set(WAITLIST_ENTRY.STATION_ID, stationId)
                .set(WAITLIST_ENTRY.STATUS, WaitListStatus.WAITING.name())
                .set(WAITLIST_ENTRY.CREATED_AT, clock.instant())
                .returning(WAITLIST_ENTRY.ID, WAITLIST_ENTRY.STATION_ID, WAITLIST_ENTRY.STATUS)
                .fetchOne()
                .map(record -> new WaitList(record.get(WAITLIST_ENTRY.ID),
                        record.get(WAITLIST_ENTRY.STATION_ID),
                        WaitListStatus.valueOf(record.get(WAITLIST_ENTRY.STATUS)),
                        record.get(WAITLIST_ENTRY.RESERVATION_ID)));
    }

    @Override
    public Optional<WaitList> findById(UUID id) {
        return dslContext.selectFrom(WAITLIST_ENTRY)
                .where(WAITLIST_ENTRY.ID.eq(id))
                .fetchOptional()
                .map(this::toWaitList);
    }

    @Override
    public Optional<WaitList> claimFirstWaiting(UUID stationId) {
        // Only flips status — no reservation_id yet. Splitting the claim from the actual bike
        // reservation means we find out whether anyone is even waiting *before* speculatively
        // reserving a bike on their behalf; if nobody is, the caller can leave the freed
        // capacity in the general pool instead of leaking it into an orphaned reservation.
        return dslContext.update(WAITLIST_ENTRY)
                .set(WAITLIST_ENTRY.STATUS, WaitListStatus.ASSIGNED.name())
                .where(WAITLIST_ENTRY.ID.eq(dslContext.select(WAITLIST_ENTRY.ID).from(WAITLIST_ENTRY)
                        .where(WAITLIST_ENTRY.STATION_ID.eq(stationId))
                        .and(WAITLIST_ENTRY.STATUS.eq(WaitListStatus.WAITING.name()))
                        .orderBy(WAITLIST_ENTRY.SEQ)
                        .limit(1)
                        .forUpdate()
                        .skipLocked()))
                .returning()
                .fetchOptional(this::toWaitList);
    }

    @Override
    public Optional<WaitList> attachReservation(UUID id, UUID reservationId) {
        return dslContext.update(WAITLIST_ENTRY)
                .set(WAITLIST_ENTRY.RESERVATION_ID, reservationId)
                .where(WAITLIST_ENTRY.ID.eq(id))
                .and(WAITLIST_ENTRY.STATUS.eq(WaitListStatus.ASSIGNED.name()))
                .returning()
                .fetchOptional(this::toWaitList);
    }

    @Override
    public Optional<WaitList> revertToWaiting(UUID id) {
        return dslContext.update(WAITLIST_ENTRY)
                .set(WAITLIST_ENTRY.STATUS, WaitListStatus.WAITING.name())
                .where(WAITLIST_ENTRY.ID.eq(id))
                .and(WAITLIST_ENTRY.STATUS.eq(WaitListStatus.ASSIGNED.name()))
                .and(WAITLIST_ENTRY.RESERVATION_ID.isNull())
                .returning()
                .fetchOptional(this::toWaitList);
    }

    private WaitList toWaitList(Record record) {
        return new WaitList(record.get(WAITLIST_ENTRY.ID),
                record.get(WAITLIST_ENTRY.STATION_ID),
                WaitListStatus.valueOf(record.get(WAITLIST_ENTRY.STATUS)),
                record.get(WAITLIST_ENTRY.RESERVATION_ID));
    }
}
