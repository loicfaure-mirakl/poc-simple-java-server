package com.example.app.bike;

import com.example.app.bike.domain.Bike;
import com.example.app.cqrs.CommandHandler;
import com.example.app.station.Station;
import com.example.app.station.StationBikeReleaseCommand;

import java.util.Optional;

public class UpdateBikeStatusCommandHandler implements CommandHandler<UpdateBikeStatusCommand, Optional<Bike>> {

    private final BikeRepository repository;
    private final CommandHandler<StationBikeReleaseCommand, Station> stationReleaseCommandHandler;

    public UpdateBikeStatusCommandHandler(BikeRepository repository, CommandHandler<StationBikeReleaseCommand, Station> stationReleaseCommandHandler) {
        this.repository = repository;
        this.stationReleaseCommandHandler = stationReleaseCommandHandler;
    }

    @Override
    public Optional<Bike> handle(UpdateBikeStatusCommand command) {
        var bike = repository.release(command.id());
        bike.ifPresent(b -> {
            stationReleaseCommandHandler.handle(new StationBikeReleaseCommand(b.stationId()));
        });
        return bike;
    }
}
