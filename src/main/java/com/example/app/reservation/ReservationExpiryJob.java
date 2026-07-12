package com.example.app.reservation;

import com.example.app.booking.ExpireReservationCommand;
import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.domain.Reservation;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

public class ReservationExpiryJob {

    private final ReservationRepository reservationRepository;
    private final CommandHandler<ExpireReservationCommand, Optional<Reservation>> expireReservationUseCase;
    private final Duration ttl;

    public ReservationExpiryJob(ReservationRepository reservationRepository, CommandHandler<ExpireReservationCommand, Optional<Reservation>> expireReservationUseCase, Clock clock, Duration ttl) {
        this.reservationRepository = reservationRepository;
        this.expireReservationUseCase = expireReservationUseCase;
        this.ttl = ttl;
    }

    public void expireStale() {
        reservationRepository.findStales(ttl).forEach(reservation ->
                expireReservationUseCase.handle(new ExpireReservationCommand(reservation.id())));
    }
}
