package com.example.app.booking;

import java.util.UUID;

public record CancelReservationCommand(UUID reservationId) {
}
