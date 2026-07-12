package com.example.app.reservation;

import com.example.app.booking.ExpireReservationCommand;
import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.domain.Reservation;
import com.example.app.reservation.domain.ReservationStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ReservationExpiryJobTest {

    @Test
    void aLostRaceOnOneRowMustNotBlockOtherStaleReservationsInTheSameSweep() {
        UUID staleButRacedId = UUID.randomUUID();
        UUID staleAndUnclaimedId = UUID.randomUUID();

        List<Reservation> stale = List.of(
                new Reservation(staleButRacedId, UUID.randomUUID(), ReservationStatus.CREATED, Instant.EPOCH),
                new Reservation(staleAndUnclaimedId, UUID.randomUUID(), ReservationStatus.CREATED, Instant.EPOCH)
        );

        List<UUID> processed = new ArrayList<>();
        CommandHandler<ExpireReservationCommand, Optional<Reservation>> expireUseCase = command -> {
            processed.add(command.reservationId());
            if (command.reservationId().equals(staleButRacedId)) {
                // a concurrent user cancel/finish already resolved this one; that's a normal,
                // silent outcome for the sweep — not an exception
                return Optional.empty();
            }
            return Optional.of(new Reservation(command.reservationId(), UUID.randomUUID(), ReservationStatus.CANCELLED, Instant.EPOCH));
        };

        ReservationExpiryJob job = new ReservationExpiryJob(
                new StubReservationRepository(stale), expireUseCase, Clock.systemUTC(), Duration.ofMinutes(1));

        assertThatCode(job::expireStale).doesNotThrowAnyException();

        // both rows from the batch get processed, regardless of one of them having already
        // been resolved by a concurrent user action
        assertThat(processed).containsExactlyInAnyOrder(staleButRacedId, staleAndUnclaimedId);
    }

    private record StubReservationRepository(List<Reservation> stale) implements ReservationRepository {
        @Override public Optional<Reservation> findById(UUID reservationId) { throw new UnsupportedOperationException(); }
        @Override public List<Reservation> findAll() { throw new UnsupportedOperationException(); }
        @Override public Reservation create(UUID bikeId) { throw new UnsupportedOperationException(); }
        @Override public Optional<Reservation> cancel(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public Optional<Reservation> finish(UUID uuid) { throw new UnsupportedOperationException(); }
        @Override public List<Reservation> findBlocked() { throw new UnsupportedOperationException(); }
        @Override public List<Reservation> findStales(Duration ttl) { return stale; }
    }
}
