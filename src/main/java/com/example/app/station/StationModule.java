package com.example.app.station;

import com.example.app.bike.BikeRepository;
import com.example.app.booking.ReserveStationBikeUseCase;
import com.example.app.reservation.CreateReservationCommandHandler;
import com.example.app.reservation.ReservationRepository;
import io.javalin.apibuilder.EndpointGroup;
import org.jooq.DSLContext;

public class StationModule {
    private StationModule() {
    }

    public static EndpointGroup routes(DSLContext dslContext, BikeRepository bikeRepository, ReservationRepository reservationRepository) {
        StationRepository stationRepository = new StationRepositoryImpl(dslContext);
        ReserveStationBikeUseCase reserveStationBikeUseCase = new ReserveStationBikeUseCase(
                bikeRepository,
                new CreateReservationCommandHandler(reservationRepository),
                stationRepository
        );
        StationController controller = new StationController(reserveStationBikeUseCase);
        return controller::registerRoutes;
    }
}
