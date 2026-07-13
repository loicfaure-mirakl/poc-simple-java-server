package com.example.app.bike;

import com.example.app.bike.domain.Bike;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BikeRepository {
    Optional<Bike> findById(UUID bikeId);

    List<Bike> findAll();

    Bike create(String code);

    Optional<Bike> reserve(UUID uuid);

    Optional<Bike> release(UUID uuid);

    Optional<Bike> reserveAny(UUID stationId);

    List<Bike> batchReserve(List<UUID> uuids);
}
