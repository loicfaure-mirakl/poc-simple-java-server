package com.example.app.reservation;

import com.example.app.reservation.domain.Reservation;
import com.example.app.reservation.domain.ReservationStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository {
    Optional<Reservation> findById(UUID reservationId);

    List<Reservation> findAll();

    Reservation create(UUID bikeId);

    Optional<Reservation> updateStatus(UUID uuid, ReservationStatus reservationStatus);
}
