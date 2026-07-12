package com.example.app.reservation;

import com.example.app.reservation.domain.Reservation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository {
    Optional<Reservation> findById(UUID reservationId);

    List<Reservation> findAll();

    Reservation create(UUID bikeId);

    Optional<Reservation> cancel(UUID uuid);

    Optional<Reservation> finish(UUID uuid);

    List<Reservation> findBlocked();
}
