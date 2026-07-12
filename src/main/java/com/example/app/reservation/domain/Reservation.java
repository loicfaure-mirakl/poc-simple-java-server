package com.example.app.reservation.domain;

import java.time.Instant;
import java.util.UUID;

public record Reservation(UUID id, UUID bikeId, ReservationStatus status, Instant createdAt) {

    public Reservation cancel() {
        if (status.canTransitionTo(ReservationStatus.CANCELLED)) {
            return new Reservation(id, bikeId, ReservationStatus.CANCELLED, createdAt);
        }
        throw new IllegalStateException("Cannot cancel reservation from status: " + status);
    }
}
