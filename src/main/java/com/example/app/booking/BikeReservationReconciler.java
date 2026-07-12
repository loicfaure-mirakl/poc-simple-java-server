package com.example.app.booking;

import com.example.app.bike.BikeRepository;
import com.example.app.bike.domain.Status;
import com.example.app.reservation.ReservationRepository;
import com.example.app.reservation.domain.Reservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catches up bikes left RESERVED after their reservation was cancelled but the release
 * (best-effort, retried inline in CancelReservationUseCase) never landed. The mismatch is
 * derived entirely from committed state — a CANCELLED reservation whose bike is still
 * RESERVED — so no outbox/event log is needed; re-running this is always safe since
 * BikeRepository.release is itself an idempotent, conditional update.
 */
public class BikeReservationReconciler {

    private static final Logger log = LoggerFactory.getLogger(BikeReservationReconciler.class);

    private final ReservationRepository reservationRepository;
    private final BikeRepository bikeRepository;

    public BikeReservationReconciler(ReservationRepository reservationRepository, BikeRepository bikeRepository) {
        this.reservationRepository = reservationRepository;
        this.bikeRepository = bikeRepository;
    }

    public void reconcileCancelledReservations() {
        for (Reservation reservation : reservationRepository.findBlocked()) {
            bikeRepository.findById(reservation.bikeId())
                    .filter(bike -> bike.status() == Status.RESERVED)
                    .ifPresent(bike -> {
                        bikeRepository.release(bike.id());
                        log.info("Reconciled cancelled reservation {}: released bike {}", reservation.id(), bike.id());
                    });
        }
    }
}
