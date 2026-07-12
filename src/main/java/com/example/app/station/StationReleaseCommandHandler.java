package com.example.app.station;

import com.example.app.cqrs.CommandHandler;
import com.example.app.error.NotFoundException;
import com.example.app.station.waitlist.FreedBikeCommand;
import com.example.app.station.waitlist.WaitList;

public class StationReleaseCommandHandler implements CommandHandler<StationBikeReleaseCommand, Station> {

    private final StationRepository repository;
    private final CommandHandler<FreedBikeCommand, WaitList> waitListCommandHandler;

    public StationReleaseCommandHandler(StationRepository repository, CommandHandler<FreedBikeCommand, WaitList> waitListCommandHandler) {
        this.repository = repository;
        this.waitListCommandHandler = waitListCommandHandler;
    }

    @Override
    public Station handle(StationBikeReleaseCommand command) {
        Station station = repository.release(command.stationId()).orElseThrow(() ->
                new NotFoundException("Station not found"));
        // Ok as this is happening on the same server
        waitListCommandHandler.handle(new FreedBikeCommand(station.id()));
        return station;
    }
}
