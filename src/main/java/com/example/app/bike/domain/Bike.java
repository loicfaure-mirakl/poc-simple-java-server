package com.example.app.bike.domain;

import java.util.UUID;

public record Bike(UUID id, String code, Status status) {
}
