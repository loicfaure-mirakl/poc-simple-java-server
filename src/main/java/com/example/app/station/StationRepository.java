package com.example.app.station;

import java.util.Optional;
import java.util.UUID;

public interface StationRepository {
    Optional<Station> claims(UUID id);

    Optional<Station> findById(UUID stationId);
}
