package com.example.app.booking;

import java.util.UUID;

public record ExpireReservationCommand(UUID reservationId) {
}
