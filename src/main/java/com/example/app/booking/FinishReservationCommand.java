package com.example.app.booking;

import java.util.UUID;

public record FinishReservationCommand(UUID reservationId) {
}
