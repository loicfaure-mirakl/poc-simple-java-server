package com.example.app.bike;

import java.util.List;
import java.util.UUID;

public record BatchReservationRequest(List<UUID> bikeIds) {
}
