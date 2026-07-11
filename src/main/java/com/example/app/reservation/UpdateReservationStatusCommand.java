package com.example.app.reservation;

import com.example.app.reservation.domain.ReservationStatus;

import java.util.UUID;

public record UpdateReservationStatusCommand(UUID reservationId, ReservationStatus reservationStatus) {
}
