package com.example.app.bike;

import com.example.app.cqrs.CommandHandler;
import com.example.app.error.ConflictException;
import com.example.app.error.NotFoundException;
import com.example.app.reservation.CreateReservationCommand;
import com.example.app.reservation.domain.Reservation;

public class ReserveBikeCommandHandler implements CommandHandler<ReserveBikeCommand, Reservation> {

    private final BikeRepository repository;
    private final CommandHandler<CreateReservationCommand, Reservation> createReservationCommand;

    public ReserveBikeCommandHandler(BikeRepository repository, CommandHandler<CreateReservationCommand, Reservation> createReservationCommand) {
        this.repository = repository;
        this.createReservationCommand = createReservationCommand;
    }

    @Override
    public Reservation handle(ReserveBikeCommand command) {
        var reserved = repository.reserve(command.bikeId());
        if (reserved.isPresent()) {
            return createReservationCommand.handle(new CreateReservationCommand(reserved.get().id()));
        }

        var bike = repository.findById(command.bikeId())
                .orElseThrow(() -> new NotFoundException("Bike not found: " + command.bikeId()));
        throw new ConflictException("Bike already reserved: " + bike.id());
    }
}
