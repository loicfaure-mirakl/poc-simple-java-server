package com.example.app.station.waitlist;

import com.example.app.cqrs.CommandHandler;
import com.example.app.reservation.domain.Reservation;
import com.example.app.station.ReserveStationBikeCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class WaitListCommandHandler implements CommandHandler<FreedBikeCommand, WaitList> {

    private static final Logger log = LoggerFactory.getLogger(WaitListCommandHandler.class);

    private final CommandHandler<ReserveStationBikeCommand, Reservation> reserveStationBikeCommandHandler;
    private final WaitListRepository waitListRepository;

    public WaitListCommandHandler(CommandHandler<ReserveStationBikeCommand, Reservation> reserveStationBikeCommandHandler, WaitListRepository waitListRepository) {
        this.reserveStationBikeCommandHandler = reserveStationBikeCommandHandler;
        this.waitListRepository = waitListRepository;
    }

    @Override
    public WaitList handle(FreedBikeCommand command) {
        // Claim the queue head *before* touching a bike: if nobody is waiting, we must not
        // speculatively reserve a bike on nobody's behalf — that would leak the freed capacity
        // into an orphaned reservation. Only once we know someone is actually waiting do we
        // spend the bike claim on them.
        Optional<WaitList> claimed = waitListRepository.claimFirstWaiting(command.stationId());
        if (claimed.isEmpty()) {
            return null;
        }

        WaitList entry = claimed.get();
        try {
            Reservation reservation = reserveStationBikeCommandHandler.handle(new ReserveStationBikeCommand(command.stationId()));
            return waitListRepository.attachReservation(entry.id(), reservation.id()).orElse(entry);
        } catch (RuntimeException e) {
            // Someone else took the freed capacity before we could hand it to the queue head
            // (e.g. a direct reservation racing the same release). Put them back in line —
            // same seq, so they keep their place — for the next release to retry.
            log.warn("Failed to claim a bike for waitlist entry {} at station {}; reverting to WAITING",
                    entry.id(), command.stationId(), e);
            return waitListRepository.revertToWaiting(entry.id()).orElse(entry);
        }
    }
}
