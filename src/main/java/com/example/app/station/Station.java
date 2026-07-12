package com.example.app.station;

import java.util.UUID;

public record Station(UUID id, String name, int capacity, int availableCount) {
}
