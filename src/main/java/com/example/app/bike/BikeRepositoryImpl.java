package com.example.app.bike;

import com.example.app.bike.domain.Bike;
import com.example.app.bike.domain.Status;
import com.example.app.jooq.generated.Tables;
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
}
