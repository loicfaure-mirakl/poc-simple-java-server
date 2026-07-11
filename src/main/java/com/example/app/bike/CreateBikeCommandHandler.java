package com.example.app.bike;

import com.example.app.bike.domain.Bike;
import com.example.app.cqrs.CommandHandler;

import java.util.UUID;

public class CreateBikeCommandHandler implements CommandHandler<CreateBikeCommand, Bike> {

    private final BikeRepository repository;

    public CreateBikeCommandHandler(BikeRepository repository) {
        this.repository = repository;
    }

    @Override
    public Bike handle(CreateBikeCommand command) {
        return repository.create(command.code());
    }
}
