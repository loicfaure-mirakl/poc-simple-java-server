package com.example.app.station.waitlist;

import java.util.Optional;
import java.util.UUID;

public interface WaitListRepository {
    WaitList join(UUID stationId);

    Optional<WaitList> findById(UUID id);

    Optional<WaitList> claimFirstWaiting(UUID stationId);

    Optional<WaitList> attachReservation(UUID id, UUID reservationId);

    Optional<WaitList> revertToWaiting(UUID id);
}
