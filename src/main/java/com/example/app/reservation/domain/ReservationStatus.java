package com.example.app.reservation.domain;

public enum ReservationStatus {
    CREATED,
    CANCELLED,
    RETURNED;

    public boolean canTransitionTo(ReservationStatus newStatus) {
        return this == CREATED && (newStatus == CANCELLED || newStatus == RETURNED);
    }
}
