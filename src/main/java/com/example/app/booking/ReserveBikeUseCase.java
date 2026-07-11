package com.example.app.booking;

import com.example.app.bike.BikeRepository;
import com.example.app.bike.domain.Bike;
import com.example.app.cqrs.CommandHandler;
import com.example.app.error.ConflictException;
import com.example.app.error.NotFoundException;
import com.example.app.reservation.CreateReservationCommand;
import com.example.app.reservation.domain.Reservation;

import java.util.UUID;

public class ReserveBikeUseCase implements CommandHandler<ReserveBikeCommand, Reservation> {

    private final BikeRepository bikeRepository;
    private final CommandHandler<CreateReservationCommand, Reservation> createReservationCommand;

    public ReserveBikeUseCase(BikeRepository bikeRepository, CommandHandler<CreateReservationCommand, Reservation> createReservationCommand) {
        this.bikeRepository = bikeRepository;
        this.createReservationCommand = createReservationCommand;
    }

    @Override
    public Reservation handle(ReserveBikeCommand command) {
        Bike bike = bikeRepository.reserve(command.bikeId())
                .orElseGet(() -> failToReserve(command.bikeId()));

        try {
            // Ok as this happen on the same server.
            return createReservationCommand.handle(new CreateReservationCommand(bike.id()));
        } catch (RuntimeException e) {
            bikeRepository.release(bike.id());
            throw e;
        }
    }

    private Bike failToReserve(UUID bikeId) {
        Bike bike = bikeRepository.findById(bikeId)
                .orElseThrow(() -> new NotFoundException("Bike not found: " + bikeId));
        throw new ConflictException("Bike already reserved: " + bike.id());
    }
}
