package com.example.app.booking;

import com.example.app.bike.UpdateBikeStatusCommand;
import com.example.app.bike.domain.Bike;
import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.ReservationRepository;
import com.example.app.reservation.domain.Reservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

public class ExpireReservationUseCase implements CommandHandler<ExpireReservationCommand, Optional<Reservation>> {

    private static final Logger log = LoggerFactory.getLogger(ExpireReservationUseCase.class);
    private static final int MAX_RELEASE_ATTEMPTS = 3;

    private final ReservationRepository reservationRepository;
    private final CommandHandler<UpdateBikeStatusCommand, Optional<Bike>> releaseBikeCommand;

    public ExpireReservationUseCase(ReservationRepository reservationRepository, CommandHandler<UpdateBikeStatusCommand, Optional<Bike>> releaseBikeCommand) {
        this.reservationRepository = reservationRepository;
        this.releaseBikeCommand = releaseBikeCommand;
    }

    @Override
    public Optional<Reservation> handle(ExpireReservationCommand command) {
        // Unlike CancelReservationUseCase, losing the race here is not an error: it just means a
        // user's own cancel/finish beat the sweep to it, which is exactly what should happen.
        // Empty is a valid, silent outcome, so the scheduled sweep never needs to catch anything
        // to keep processing the rest of a batch.
        Optional<Reservation> reservation = reservationRepository.cancel(command.reservationId());
        reservation.ifPresent(r -> releaseBikeWithRetry(r.bikeId()));
        return reservation;
    }

    private void releaseBikeWithRetry(UUID bikeId) {
        for (int attempt = 1; attempt <= MAX_RELEASE_ATTEMPTS; attempt++) {
            try {
                releaseBikeCommand.handle(new UpdateBikeStatusCommand(bikeId));
                return;
            } catch (RuntimeException e) {
                if (attempt == MAX_RELEASE_ATTEMPTS) {
                    log.warn("Failed to release bike {} after {} attempts following a reservation expiry; " +
                            "leaving it for BikeReservationReconciler", bikeId, attempt, e);
                    return;
                }
                sleep(attempt);
            }
        }
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(50L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
