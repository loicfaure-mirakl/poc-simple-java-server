package com.example.app.bike;

import com.example.app.bike.domain.Bike;
import com.example.app.cqrs.CommandHandler;

import java.util.Optional;

public class UpdateBikeStatusCommandHandler implements CommandHandler<UpdateBikeStatusCommand, Optional<Bike>> {

    private final BikeRepository repository;

    public UpdateBikeStatusCommandHandler(BikeRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Bike> handle(UpdateBikeStatusCommand command) {
        return repository.release(command.id());
    }
}
