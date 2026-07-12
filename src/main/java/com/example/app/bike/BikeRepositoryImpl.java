package com.example.app.bike;

import com.example.app.bike.domain.Bike;
import com.example.app.bike.domain.Status;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.example.app.jooq.generated.Tables.BIKE;

public class BikeRepositoryImpl implements BikeRepository {
    private final DSLContext dsl;
    public BikeRepositoryImpl(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Bike> findById(UUID bikeId) {
        return dsl.selectFrom(BIKE)
                .where(BIKE.ID.eq(bikeId))
                .fetchOptionalInto(Bike.class);
    }

    @Override
    public List<Bike> findAll() {
        return dsl.selectFrom(BIKE)
                .fetchInto(Bike.class);
    }

    @Override
    public Bike create(String code) {
        return dsl.insertInto(BIKE)
                .set(BIKE.ID, UUID.randomUUID())
                .set(BIKE.CODE, code)
                .set(BIKE.STATUS, Status.AVAILABLE.name())
                .returning()
                .fetchOneInto(Bike.class);
    }

    @Override
    public Optional<Bike> reserve(UUID uuid) {
        return dsl.update(BIKE)
                .set(BIKE.STATUS, Status.RESERVED.name())
                .where(BIKE.ID.eq(uuid))
                .and(BIKE.STATUS.eq(Status.AVAILABLE.name()))
                .returning()
                .fetchOptionalInto(Bike.class);
    }

    @Override
    public Optional<Bike> release(UUID uuid) {
        return dsl.update(BIKE)
                .set(BIKE.STATUS, Status.AVAILABLE.name())
                .where(BIKE.ID.eq(uuid))
                .and(BIKE.STATUS.eq(Status.RESERVED.name()))
                .returning()
                .fetchOptionalInto(Bike.class);
    }

    @Override
    public Optional<Bike> reserveAny(UUID stationId) {
        // WHERE id IN (SELECT ... LIMIT 1) has no locking during selection, so concurrent
        // transactions can all pick the SAME candidate row before anyone commits — only one
        // wins, the rest get zero rows and never retry a different bike. FOR UPDATE SKIP LOCKED
        // fixes this: each transaction locks a row as it selects it and skips over rows already
        // locked by someone else, so concurrent callers fan out across distinct rows instead of
        // colliding on the same one.
        return dsl.update(BIKE)
                .set(BIKE.STATUS, Status.RESERVED.name())
                .where(BIKE.ID.eq(
                        dsl.select(BIKE.ID)
                                .from(BIKE)
                                .where(BIKE.STATION_ID.eq(stationId))
                                .and(BIKE.STATUS.eq(Status.AVAILABLE.name()))
                                .orderBy(BIKE.ID)
                                .limit(1)
                                .forUpdate()
                                .skipLocked()
                ))
                .returning()
                .fetchOptionalInto(Bike.class);
    }
}
