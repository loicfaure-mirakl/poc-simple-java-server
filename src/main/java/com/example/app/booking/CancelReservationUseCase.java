package com.example.app.booking;

import com.example.app.bike.UpdateBikeStatusCommand;
import com.example.app.bike.domain.Bike;
import com.example.app.cqrs.CommandHandler;
import com.example.app.error.ConflictException;
import com.example.app.error.NotFoundException;
import com.example.app.reservation.ReservationRepository;
import com.example.app.reservation.domain.Reservation;
import com.example.app.reservation.domain.ReservationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

public class CancelReservationUseCase implements CommandHandler<CancelReservationCommand, Reservation> {

    private static final Logger log = LoggerFactory.getLogger(CancelReservationUseCase.class);
    private static final int MAX_RELEASE_ATTEMPTS = 3;

    private final ReservationRepository reservationRepository;
    private final CommandHandler<UpdateBikeStatusCommand, Optional<Bike>> releaseBikeCommand;

    public CancelReservationUseCase(ReservationRepository reservationRepository, CommandHandler<UpdateBikeStatusCommand, Optional<Bike>> releaseBikeCommand) {
        this.reservationRepository = reservationRepository;
        this.releaseBikeCommand = releaseBikeCommand;
    }

    @Override
    public Reservation handle(CancelReservationCommand command) {
        // Cancellation is the durable, primary fact here — it's already committed and correct
        // the moment cancel succeeds. Releasing the bike is a necessary but separate
        // side effect on the other aggregate: if it keeps failing we do NOT undo the
        // cancellation (that would misrepresent a decision the caller already made); we retry
        // a few times inline, and if it's still stuck, BikeReservationReconciler catches up later.
        Reservation reservation = reservationRepository.cancel(command.reservationId())
                .orElseGet(() -> failToCancel(command.reservationId()));

        releaseBikeWithRetry(reservation.bikeId());
        return reservation;
    }

    private void releaseBikeWithRetry(UUID bikeId) {
        for (int attempt = 1; attempt <= MAX_RELEASE_ATTEMPTS; attempt++) {
            try {
                releaseBikeCommand.handle(new UpdateBikeStatusCommand(bikeId));
                return;
            } catch (RuntimeException e) {
                if (attempt == MAX_RELEASE_ATTEMPTS) {
                    log.warn("Failed to release bike {} after {} attempts following a reservation cancellation; " +
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

    private Reservation failToCancel(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new NotFoundException("Reservation not found: " + reservationId));
        if (reservation.status().equals(ReservationStatus.CANCELLED)) {
            throw new ConflictException("Reservation is already cancelled: " + reservation.id());
        }
        throw new ConflictException("Reservation cannot be cancelled from status " + reservation.status() + ": " + reservation.id());
    }
}
