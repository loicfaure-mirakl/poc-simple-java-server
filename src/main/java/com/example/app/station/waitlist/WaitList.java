package com.example.app.station.waitlist;

import java.util.UUID;

public record WaitList(UUID id, UUID stationId, WaitListStatus status, UUID reservationId) {
}
