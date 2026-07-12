package com.example.app.station;

import org.jooq.DSLContext;

import java.util.Optional;
import java.util.UUID;

import static com.example.app.jooq.generated.Tables.STATION;

public class StationRepositoryImpl implements StationRepository {
    private final DSLContext dsl;

    public StationRepositoryImpl(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Station> claims(UUID id) {
        return dsl.update(STATION)
                .set(STATION.AVAILABLE_COUNT, STATION.AVAILABLE_COUNT.minus(1))
                .where(STATION.ID.eq(id))
                .and(STATION.AVAILABLE_COUNT.gt(0))
                .returning()
                .fetchOptionalInto(Station.class);
    }

    @Override
    public Optional<Station> findById(UUID stationId) {
        return dsl.selectFrom(STATION)
                .where(STATION.ID.eq(stationId))
                .fetchOptionalInto(Station.class);
    }
}
