package com.example.app.reservation;

import com.example.app.reservation.domain.Reservation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ReservationQueryService {

    private final ReservationRepository reservationRepository;

    public ReservationQueryService(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }


    public Optional<Reservation> findById(UUID reservationId) {
        return reservationRepository.findById(reservationId);
    }

    public List<Reservation> findAll() {
        return reservationRepository.findAll();
    }
}
