package com.example.app.reservation;

import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.domain.Reservation;

import java.util.Optional;

public class UpdateReservationStatusCommandHandler implements CommandHandler<UpdateReservationStatusCommand, Optional<Reservation>> {

    private final ReservationRepository reservationRepository;

    public UpdateReservationStatusCommandHandler(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    @Override
    public Optional<Reservation> handle(UpdateReservationStatusCommand command) {
        return reservationRepository.updateStatus(command.reservationId(), command.reservationStatus());
    }
}
