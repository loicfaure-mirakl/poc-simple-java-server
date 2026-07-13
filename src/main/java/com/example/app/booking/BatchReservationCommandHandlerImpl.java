package com.example.app.booking;

import com.example.app.bike.BikeRepository;
import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.domain.Reservation;

import java.util.List;

public class BatchReservationCommandHandlerImpl implements CommandHandler<BatchReservationCommand, List<Reservation>> {
    private final BikeRepository repository;

    public BatchReservationCommandHandlerImpl(BikeRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Reservation> handle(BatchReservationCommand command) {
        var bikes = repository.batchReserve(command.bikeIds());
        if (bikes.size() != command.bikeIds().size()) {
            // Unable to reserve all bikes, reverting
            repository.batchCancel(bikes);
        }
        return List.of();
    }
}
