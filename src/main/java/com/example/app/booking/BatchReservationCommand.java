package com.example.app.booking;

import java.util.List;
import java.util.UUID;

public record BatchReservationCommand(List<UUID> bikeIds) {

}
