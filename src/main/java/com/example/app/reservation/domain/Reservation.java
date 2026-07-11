package com.example.app.reservation.domain;

import java.time.Instant;
import java.util.UUID;

public record Reservation(UUID id, UUID bikeId, String status, Instant createdAt) {
}
