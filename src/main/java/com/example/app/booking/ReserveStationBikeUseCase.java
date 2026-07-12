package com.example.app.booking;

import com.example.app.bike.BikeRepository;
import com.example.app.bike.domain.Bike;
import com.example.app.cqrs.CommandHandler;
import com.example.app.error.ConflictException;
import com.example.app.error.NotFoundException;
import com.example.app.reservation.CreateReservationCommand;
import com.example.app.reservation.domain.Reservation;
import com.example.app.station.ReserveStationBikeCommand;
import com.example.app.station.Station;
import com.example.app.station.StationRepository;

import java.util.UUID;

public class ReserveStationBikeUseCase implements CommandHandler<ReserveStationBikeCommand, Reservation> {

    private final BikeRepository bikeRepository;
    private final CommandHandler<CreateReservationCommand, Reservation> createReservationCommand;
    private final StationRepository stationRepository;

    public ReserveStationBikeUseCase(BikeRepository bikeRepository, CommandHandler<CreateReservationCommand, Reservation> createReservationCommand, StationRepository stationRepository) {
        this.bikeRepository = bikeRepository;
        this.createReservationCommand = createReservationCommand;
        this.stationRepository = stationRepository;
    }

    /// Verify station has at least one bike available => minus 1 if > 0 on one update.
    /// Make bike unavailable
    /// Create Reservation
    @Override
    public Reservation handle(ReserveStationBikeCommand command) {
        stationRepository.claims(command.stationId())
                .orElseGet(() -> failToClaims(command.stationId()));

        Bike bike = bikeRepository.reserveAny(command.stationId())
                .orElseGet(() -> failToReserve(command.stationId()));

        try {
            // Ok as this happen on the same server.
            return createReservationCommand.handle(new CreateReservationCommand(bike.id()));
        } catch (RuntimeException e) {
            bikeRepository.release(bike.id());
            throw e;
        }
    }

    private Station failToClaims(UUID stationId) {
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new NotFoundException("Station not found: " + stationId));
        throw new ConflictException("No bike available at station: " + station.id());
    }

    private Bike failToReserve(UUID stationId) {
        throw new ConflictException("No bike available for: " + stationId);
    }
}
