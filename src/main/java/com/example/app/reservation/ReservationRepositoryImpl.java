package com.example.app.reservation;

import com.example.app.reservation.domain.Reservation;
import com.example.app.reservation.domain.ReservationStatus;
import org.jooq.DSLContext;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.example.app.jooq.generated.Tables.RESERVATION;

public class ReservationRepositoryImpl implements ReservationRepository {
    private final DSLContext dsl;
    private final Clock clock;
    public ReservationRepositoryImpl(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    @Override
    public Optional<Reservation> findById(UUID reservationId) {
        return dsl.selectFrom(RESERVATION)
                .where(RESERVATION.ID.eq(reservationId))
                .fetchOptionalInto(Reservation.class);
    }

    @Override
    public List<Reservation> findAll() {
        return dsl.selectFrom(RESERVATION)
                .fetchInto(Reservation.class);
    }

    @Override
    public Reservation create(UUID bikeId) {
        return dsl.insertInto(RESERVATION)
                .set(RESERVATION.ID, UUID.randomUUID())
                .set(RESERVATION.BIKE_ID, bikeId)
                .set(RESERVATION.STATUS, ReservationStatus.CREATED.name())
                .set(RESERVATION.CREATED_AT, clock.instant())
                .returning()
                .fetchOneInto(Reservation.class);
    }

    @Override
    public Optional<Reservation> updateStatus(UUID uuid, ReservationStatus reservationStatus) {
        dsl.update(RESERVATION)
                .set(RESERVATION.STATUS, reservationStatus.name())
                .where(RESERVATION.ID.eq(uuid))
                .execute();
        return dsl.selectFrom(RESERVATION)
                .where(RESERVATION.ID.eq(uuid))
                .fetchOptionalInto(Reservation.class);
    }
}
