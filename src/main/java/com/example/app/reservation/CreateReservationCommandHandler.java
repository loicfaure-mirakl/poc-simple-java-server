package com.example.app.reservation;

import com.example.app.reservation.domain.Reservation;
import com.example.app.cqrs.CommandHandler;

public class CreateReservationCommandHandler implements CommandHandler<CreateReservationCommand, Reservation> {

    private final ReservationRepository repository;

    public CreateReservationCommandHandler(ReservationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Reservation handle(CreateReservationCommand command) {
        return repository.create(command.bikeId());
    }
}
