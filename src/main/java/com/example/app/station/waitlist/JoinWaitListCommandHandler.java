package com.example.app.station.waitlist;

import com.example.app.cqrs.CommandHandler;
import com.example.app.error.NotFoundException;
import com.example.app.station.StationRepository;

public class JoinWaitListCommandHandler implements CommandHandler<WaitListCommand, WaitList> {
    private final WaitListRepository waitListRepository;
    private final StationRepository stationRepository;
    private final CommandHandler<FreedBikeCommand, WaitList> waitListCommandHandler;

    public JoinWaitListCommandHandler(WaitListRepository waitListRepository, StationRepository stationRepository, CommandHandler<FreedBikeCommand, WaitList> waitListCommandHandler) {
        this.waitListRepository = waitListRepository;
        this.stationRepository = stationRepository;
        this.waitListCommandHandler = waitListCommandHandler;
    }

    @Override
    public WaitList handle(WaitListCommand command) {
        if (stationRepository.findById(command.stationId()).isEmpty()) {
            throw new NotFoundException("Station with id " + command.stationId() + " not found");
        }

        WaitList waitList = waitListRepository.join(command.stationId());

        // A bike may already be free right now (nobody was waiting when it was released). Try
        // to serve the queue head immediately — same claim-then-reserve logic a release uses —
        // rather than leaving this joiner stuck in WAITING until some future release happens.
        // This always serves whoever is actually first by seq, not necessarily this joiner.
        WaitList served = waitListCommandHandler.handle(new FreedBikeCommand(command.stationId()));
        if (served != null && served.id().equals(waitList.id())) {
            return served;
        }
        return waitList;
    }
}
